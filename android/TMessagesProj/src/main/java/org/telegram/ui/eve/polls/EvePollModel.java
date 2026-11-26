package org.telegram.ui.eve.polls;

import androidx.annotation.Nullable;

import org.telegram.ui.eve.admin.EveAdminModels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared immutable poll model rendered by {@link EveTelegramPollCardView} for both
 * Student Announcements & Polls and Admin Polls Manager.
 */
public final class EvePollModel {

    public final String id;
    public final String question;
    public final List<String> options;
    public final Map<Integer, Integer> voteCounts;
    public final int totalVotes;
    public final boolean active;
    public final long createdAt;
    public final long endsAt;
    public final String createdBy;
    @Nullable
    public final Integer myVoteIndex;

    public EvePollModel(
            String id,
            String question,
            List<String> options,
            Map<Integer, Integer> voteCounts,
            int totalVotes,
            boolean active,
            long createdAt,
            long endsAt,
            String createdBy,
            @Nullable Integer myVoteIndex) {
        this.id = id != null ? id : "";
        this.question = question != null ? question : "";
        this.options = options != null ? Collections.unmodifiableList(new ArrayList<>(options)) : Collections.emptyList();
        this.voteCounts = voteCounts != null ? Collections.unmodifiableMap(new HashMap<>(voteCounts)) : Collections.emptyMap();
        this.totalVotes = Math.max(0, totalVotes);
        this.active = active;
        this.createdAt = createdAt;
        this.endsAt = endsAt;
        this.createdBy = createdBy != null ? createdBy : "";
        this.myVoteIndex = myVoteIndex;
    }

    public static EvePollModel fromAdminItem(EveAdminModels.PollItem item) {
        return new EvePollModel(
                item.id,
                item.question,
                item.options,
                item.voteCounts,
                item.totalVotes,
                item.active,
                item.createdAt,
                item.endsAt,
                item.createdBy,
                null
        );
    }

    public boolean isExpired(long nowMs) {
        return endsAt > 0L && nowMs > endsAt;
    }

    public boolean isOpen(long nowMs) {
        return active && !isExpired(nowMs);
    }

    public int getOptionVotes(int index) {
        Integer count = voteCounts.get(index);
        return count != null ? Math.max(0, count) : 0;
    }

    public int getWinningVotes() {
        int best = 0;
        for (int i = 0; i < options.size(); i++) {
            int c = getOptionVotes(i);
            if (c > best) {
                best = c;
            }
        }
        return best;
    }

    public EvePollModel withRecordedVote(int optionIndex) {
        if (optionIndex < 0 || optionIndex >= options.size() || myVoteIndex != null) {
            return this;
        }
        Map<Integer, Integer> updated = new HashMap<>(voteCounts);
        updated.put(optionIndex, getOptionVotes(optionIndex) + 1);
        return new EvePollModel(
                id, question, options, updated, totalVotes + 1,
                active, createdAt, endsAt, createdBy, optionIndex
        );
    }
}
