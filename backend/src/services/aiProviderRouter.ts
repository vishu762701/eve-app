// ============================================================================
// Unified Multi-Provider AI Test Generation Router
// Supports OpenRouter, Groq, OpenAI, Gemini, and automatic failover ("auto").
// ============================================================================

import {
  buildPrompt,
  GeminiErrorCode,
  GeminiProviderError,
  GeneratedQuestionItem,
  generateQuestions as generateWithGemini,
  getGeminiApiKeys,
  getModelChain,
  normalizeQuestionText,
  parseAndValidateQuestions,
  PromptGenerationOptions,
  redactSecrets,
} from "../ai/generator";
import { Env } from "../types";
import { normalizeAiProvider, readAppConfig } from "../util/appConfig";
import { trackGeminiCall } from "../util/usageTracker";

export type AiConcreteProvider = "openrouter" | "groq" | "openai" | "gemini";
export type AiProviderMode = "auto" | AiConcreteProvider;

export interface AiRouterResult {
  questions: GeneratedQuestionItem[];
  provider: AiConcreteProvider;
  model: string;
}

export interface AiRouterOptions extends PromptGenerationOptions {
  aiProvider?: string;
  aiModel?: string;
}

export const AUTO_PROVIDER_PRIORITY: AiConcreteProvider[] = [
  "openrouter",
  "groq",
  "openai",
  "gemini",
];

export const DEFAULT_MODELS_BY_PROVIDER: Record<AiConcreteProvider, string> = {
  openrouter: "google/gemini-2.5-pro",
  groq: "llama-3.3-70b-versatile",
  openai: "gpt-4o-mini",
  gemini: "gemini-2.5-flash",
};

const OPENAI_COMPAT_ENDPOINTS: Record<Exclude<AiConcreteProvider, "gemini">, string> = {
  openrouter: "https://openrouter.ai/api/v1/chat/completions",
  groq: "https://api.groq.com/openai/v1/chat/completions",
  openai: "https://api.openai.com/v1/chat/completions",
};

function getApiKeyForProvider(env: Env, provider: AiConcreteProvider): string {
  switch (provider) {
    case "openrouter":
      return String(env.OPENROUTER_API_KEY || "").trim();
    case "groq":
      return String(env.GROQ_API_KEY || "").trim();
    case "openai":
      return String(env.OPENAI_API_KEY || "").trim();
    case "gemini":
      return getGeminiApiKeys(env)[0] || "";
  }
}

export function getConfiguredProviders(env: Env): AiConcreteProvider[] {
  return AUTO_PROVIDER_PRIORITY.filter((p) => Boolean(getApiKeyForProvider(env, p)));
}

function classifyHttpFailure(
  provider: AiConcreteProvider,
  model: string,
  status: number,
  bodyText: string,
  apiKey: string
): GeminiProviderError {
  const safeBody = redactSecrets(bodyText, [apiKey]).slice(0, 300);
  if (status === 401 || status === 403) {
    return new GeminiProviderError(
      GeminiErrorCode.AUTH_INVALID,
      status,
      `${provider} authentication failed (${status}).`,
      { model, providerMessage: safeBody, isCredentialFailure: true, isTransient: true }
    );
  }
  if (status === 429) {
    return new GeminiProviderError(
      GeminiErrorCode.RATE_LIMITED,
      429,
      `${provider} rate limit/quota reached.`,
      { model, providerMessage: safeBody, isTransient: true }
    );
  }
  if (status >= 500 || status === 408) {
    return new GeminiProviderError(
      GeminiErrorCode.TEMPORARY_PROVIDER_ERROR,
      status,
      `${provider} returned temporary server error (${status}).`,
      { model, providerMessage: safeBody, isTransient: true }
    );
  }
  return new GeminiProviderError(
    GeminiErrorCode.TEMPORARY_PROVIDER_ERROR,
    status || 502,
    `${provider} rejected request (${status}).`,
    { model, providerMessage: safeBody, isTransient: false }
  );
}

async function generateWithOpenAiCompatible(
  provider: Exclude<AiConcreteProvider, "gemini">,
  apiKey: string,
  model: string,
  examName: string,
  syllabus: string,
  targetCount: number,
  customPrompt: string,
  fetchFn: typeof fetch,
  options: PromptGenerationOptions,
  db?: D1Database
): Promise<GeneratedQuestionItem[]> {
  const endpoint = OPENAI_COMPAT_ENDPOINTS[provider];
  const CHUNK_SIZE = 10;
  const collected: GeneratedQuestionItem[] = [];
  const seenTexts = new Set<string>(options.excludedNormalizedTexts || []);
  const MAX_EXTRA_ROUNDS = 2;
  let extraRounds = 0;

  while (collected.length < targetCount) {
    const neededCount = Math.min(CHUNK_SIZE, targetCount - collected.length);
    if (neededCount <= 0) break;

    const prompt = buildPrompt(examName, syllabus, neededCount, customPrompt, options);
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    };
    if (provider === "openrouter") {
      headers["HTTP-Referer"] = "https://eve-mock-test.app";
      headers["X-Title"] = "Eve Mock Test Factory";
    }

    let chunkQuestions: GeneratedQuestionItem[] | null = null;
    let lastError: GeminiProviderError | null = null;

    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        const res = await fetchFn(endpoint, {
          method: "POST",
          headers,
          body: JSON.stringify({
            model,
            messages: [
              {
                role: "system",
                content:
                  "You are a Senior Indian Competitive Exam Paper-Setter. Output ONLY a valid JSON array with no markdown or commentary.",
              },
              { role: "user", content: prompt },
            ],
            temperature: 0.4,
          }),
          signal: AbortSignal.timeout(30_000),
        });

        if (!res.ok) {
          if (db) await trackGeminiCall(db, false).catch(() => {});
          const errBody = await res.text().catch(() => "");
          lastError = classifyHttpFailure(provider, model, res.status, errBody, apiKey);
          if ((res.status === 429 || res.status >= 500 || res.status === 408) && attempt < 2) {
            await new Promise((r) => setTimeout(r, 600));
            continue;
          }
          throw lastError;
        }

        const payload = (await res.json()) as any;
        const rawText = String(payload?.choices?.[0]?.message?.content || "").trim();
        if (!rawText) {
          throw new GeminiProviderError(
            GeminiErrorCode.INVALID_GEMINI_RESPONSE,
            502,
            `${provider} returned an empty response.`,
            { model, isTransient: true }
          );
        }

        const parsed = parseAndValidateQuestions(rawText);
        if (!parsed.length) {
          throw new GeminiProviderError(
            GeminiErrorCode.INVALID_GENERATED_QUESTIONS,
            502,
            `${provider} returned zero valid questions.`,
            { model, isTransient: true }
          );
        }
        if (db) await trackGeminiCall(db, true).catch(() => {});
        chunkQuestions = parsed;
        break;
      } catch (err: any) {
        if (err instanceof GeminiProviderError) {
          lastError = err;
          if (!err.isTransient || attempt >= 2) throw err;
        } else {
          if (db) await trackGeminiCall(db, false).catch(() => {});
          const message = redactSecrets(String(err?.message || "Network timeout"), [apiKey]);
          lastError = new GeminiProviderError(
            GeminiErrorCode.NETWORK_ERROR,
            503,
            `${provider} request failed or timed out: ${message}`,
            { model, isTransient: true }
          );
          if (attempt >= 2) throw lastError;
        }
        await new Promise((r) => setTimeout(r, 500));
      }
    }

    if (!chunkQuestions) {
      throw (
        lastError ||
        new GeminiProviderError(
          GeminiErrorCode.TEMPORARY_PROVIDER_ERROR,
          503,
          `${provider} failed to generate questions.`,
          { model, isTransient: true }
        )
      );
    }

    let added = 0;
    for (const q of chunkQuestions) {
      const norm = normalizeQuestionText(q.questionText);
      if (norm && !seenTexts.has(norm)) {
        seenTexts.add(norm);
        collected.push(q);
        added++;
        if (collected.length >= targetCount) break;
      }
    }

    if (collected.length < targetCount && added < neededCount) {
      if (added === 0 || extraRounds >= MAX_EXTRA_ROUNDS) break;
      extraRounds++;
    }
  }

  if (collected.length !== targetCount) {
    throw new GeminiProviderError(
      GeminiErrorCode.INVALID_GENERATED_QUESTIONS,
      502,
      `${provider} produced ${collected.length} of ${targetCount} unique questions.`,
      { model, isTransient: true }
    );
  }

  return collected;
}

/**
 * Generates questions using the configured AI provider (`openrouter`, `groq`,
 * `openai`, `gemini`) or `auto` priority failover across configured providers.
 */
export async function generateQuestionsWithRouter(
  env: Env,
  examName: string,
  syllabus: string,
  targetCount: number,
  customPrompt: string,
  fetchFn: typeof fetch = fetch,
  options: AiRouterOptions = {}
): Promise<AiRouterResult> {
  let configProvider = options.aiProvider;
  let configModel = options.aiModel;

  if ((!configProvider || configModel === undefined) && env.DB) {
    try {
      const cfg = await readAppConfig(env.DB);
      if (!configProvider) configProvider = cfg.aiProvider;
      if (configModel === undefined) configModel = cfg.aiModel;
    } catch {}
  }

  const mode = normalizeAiProvider(configProvider);
  const explicitModel = String(configModel || "").trim();

  let candidateProviders: AiConcreteProvider[];
  if (mode === "auto") {
    candidateProviders = getConfiguredProviders(env);
    if (candidateProviders.length === 0) {
      throw new GeminiProviderError(
        GeminiErrorCode.CONFIGURATION_MISSING,
        500,
        "No AI provider API key is configured (OPENROUTER_API_KEY, GROQ_API_KEY, OPENAI_API_KEY, or GEMINI_API_KEY)."
      );
    }
  } else {
    const key = getApiKeyForProvider(env, mode);
    if (!key) {
      throw new GeminiProviderError(
        GeminiErrorCode.CONFIGURATION_MISSING,
        500,
        `Selected AI provider '${mode}' is missing its API key.`
      );
    }
    candidateProviders = [mode];
  }

  let lastError: unknown = null;

  for (const provider of candidateProviders) {
    const modelForProvider =
      mode === "auto"
        ? explicitModel && candidateProviders.length === 1
          ? explicitModel
          : DEFAULT_MODELS_BY_PROVIDER[provider]
        : explicitModel || DEFAULT_MODELS_BY_PROVIDER[provider];

    try {
      if (provider === "gemini") {
        const preferredGeminiModel =
          explicitModel && (mode === "gemini" || !explicitModel.includes("/"))
            ? explicitModel
            : getModelChain(env)[0] || DEFAULT_MODELS_BY_PROVIDER.gemini;
        const questions = await generateWithGemini(
          env,
          examName,
          syllabus,
          targetCount,
          customPrompt,
          fetchFn,
          {
            ...options,
            preferredModel: preferredGeminiModel,
          }
        );
        return { questions, provider: "gemini", model: preferredGeminiModel };
      }

      const apiKey = getApiKeyForProvider(env, provider);
      const questions = await generateWithOpenAiCompatible(
        provider,
        apiKey,
        modelForProvider,
        examName,
        syllabus,
        targetCount,
        customPrompt,
        fetchFn,
        options,
        env.DB
      );
      return { questions, provider, model: modelForProvider };
    } catch (err) {
      lastError = err;
      if (mode !== "auto") {
        throw err;
      }
      console.warn(
        `[AI Router] Provider '${provider}' failed in auto mode; failing over if another provider is available.`
      );
    }
  }

  throw (
    lastError ||
    new GeminiProviderError(
      GeminiErrorCode.TEMPORARY_PROVIDER_ERROR,
      503,
      "All configured AI providers failed. Please retry shortly.",
      { isTransient: true }
    )
  );
}
