package com.eve.app.telegram.exams;

import org.telegram.ui.Cells.DialogCell;

import java.util.ArrayList;
import java.util.List;

public class ExamListFakeData {

    public static List<DialogCell.CustomDialog> getFakeExams() {
        List<DialogCell.CustomDialog> list = new ArrayList<>();

        // 1. RPSC 2nd Grade Teacher | Next test: Full Mock 14 | 8:19 PM | badge 3
        DialogCell.CustomDialog rpsc = new DialogCell.CustomDialog();
        rpsc.id = 1;
        rpsc.name = "RPSC 2nd Grade Teacher";
        rpsc.message = "Next test: Full Mock 14";
        rpsc.dateString = "8:19 PM";
        rpsc.unread_count = 3;
        rpsc.pinned = false;
        rpsc.muted = false;
        rpsc.sent = -1;
        list.add(rpsc);

        // 2. RSSB Patwari | Live now: Mock 7 | 7:53 PM | badge 2
        DialogCell.CustomDialog patwari = new DialogCell.CustomDialog();
        patwari.id = 2;
        patwari.name = "RSSB Patwari";
        patwari.message = "Live now: Mock 7";
        patwari.dateString = "7:53 PM";
        patwari.unread_count = 2;
        patwari.pinned = false;
        patwari.muted = false;
        patwari.sent = -1;
        list.add(patwari);

        // 3. Rajasthan Police Constable | Next test: Sectional 3 | Nov 14 | pinned
        DialogCell.CustomDialog police = new DialogCell.CustomDialog();
        police.id = 3;
        police.name = "Rajasthan Police Constable";
        police.message = "Next test: Sectional 3";
        police.dateString = "Nov 14";
        police.unread_count = 0;
        police.pinned = true;
        police.muted = false;
        police.sent = -1;
        list.add(police);

        // 4. CTET Paper 2 | All tests completed | Oct 26 | pinned, double tick
        DialogCell.CustomDialog ctet = new DialogCell.CustomDialog();
        ctet.id = 4;
        ctet.name = "CTET Paper 2";
        ctet.message = "All tests completed";
        ctet.dateString = "Oct 26";
        ctet.unread_count = 0;
        ctet.pinned = true;
        ctet.muted = false;
        ctet.sent = DialogCell.SENT_STATE_READ;
        list.add(ctet);

        // 5. UPSC Civil Services Preliminary Examination General Studies | Next test: GS Mock 9 | 6:50 PM | muted, badge 2 (grey)
        DialogCell.CustomDialog upsc = new DialogCell.CustomDialog();
        upsc.id = 5;
        upsc.name = "UPSC Civil Services Preliminary Examination General Studies";
        upsc.message = "Next test: GS Mock 9";
        upsc.dateString = "6:50 PM";
        upsc.unread_count = 2;
        upsc.pinned = false;
        upsc.muted = true;
        upsc.sent = -1;
        list.add(upsc);

        // 6. SSC CGL | No tests scheduled | Nov 2 | muted
        DialogCell.CustomDialog ssc = new DialogCell.CustomDialog();
        ssc.id = 6;
        ssc.name = "SSC CGL";
        ssc.message = "No tests scheduled";
        ssc.dateString = "Nov 2";
        ssc.unread_count = 0;
        ssc.pinned = false;
        ssc.muted = true;
        ssc.sent = -1;
        list.add(ssc);

        // 7. NEET UG | Next test: Biology Mock 3 | 5:12 PM | badge 11
        DialogCell.CustomDialog neet = new DialogCell.CustomDialog();
        neet.id = 7;
        neet.name = "NEET UG";
        neet.message = "Next test: Biology Mock 3";
        neet.dateString = "5:12 PM";
        neet.unread_count = 11;
        neet.pinned = false;
        neet.muted = false;
        neet.sent = -1;
        list.add(neet);

        // 8. REET Mains | Next test: Pedagogy Mock 2 | Yesterday | none
        DialogCell.CustomDialog reet = new DialogCell.CustomDialog();
        reet.id = 8;
        reet.name = "REET Mains";
        reet.message = "Next test: Pedagogy Mock 2";
        reet.dateString = "Yesterday";
        reet.unread_count = 0;
        reet.pinned = false;
        reet.muted = false;
        reet.sent = -1;
        list.add(reet);

        return list;
    }
}
