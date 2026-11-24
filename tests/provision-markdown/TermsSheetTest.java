package com.sevtinge.hyperceiler.provision.widget;

import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import com.sevtinge.hyperceiler.provision.R;
import fan.bottomsheet.BottomSheetModal;

public final class TermsSheetTest {
    private record Sheet(TermsAndStatementBottomSheet sheet, BottomSheetModal modal, View progress,
                         MarkdownView markdown, TextView tip) {
        static Sheet create() {
            var sheet = new TermsAndStatementBottomSheet(new FragmentActivity());
            var modal = BottomSheetModal.last;
            var root = modal.getRootView();
            return new Sheet(sheet, modal, root.findViewById(R.id.progress_bar),
                root.findViewById(R.id.markdown), root.findViewById(R.id.verification_code_tip));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void dispatch() {
        Handler.QUEUE.removeFirst().run();
    }

    public static void main(String[] args) {
        var first = Sheet.create();
        first.sheet().loadMarkdown("protocol");
        first.sheet().show();
        first.modal().dismiss();
        var second = Sheet.create();
        second.sheet().loadMarkdown("privacy");
        second.sheet().show();
        dispatch();
        check(first.markdown().getText().length() == 0, "dismissed sheet must not render queued content");
        check(second.progress().getVisibility() == View.VISIBLE && second.tip().getVisibility() == View.GONE,
            "old sheet must not complete the new sheet");
        dispatch();
        check(second.progress().getVisibility() == View.INVISIBLE, "new sheet did not finish loading");
        check(second.markdown().getText().toString().contains("privacy"), "fallback must use this sheet's URL");
        check("Code 2".contentEquals(second.tip().getText()), "verification code belongs to this sheet");

        var fallback = (SpannableStringBuilder) second.markdown().getText();
        fallback.links.getFirst().onClick(second.markdown());
        check(second.progress().getVisibility() == View.VISIBLE && second.tip().getVisibility() == View.GONE,
            "retry must restore loading state");
        dispatch();
        check(second.markdown().getText().toString().contains("privacy"), "retry must retain its own URL");
        check("Code 3".contentEquals(second.tip().getText()), "retry must refresh the verification code");

        second.sheet().loadMarkdown("superseded");
        second.sheet().loadMarkdown("current");
        dispatch();
        check(second.progress().getVisibility() == View.VISIBLE, "superseded result ended the current load");
        dispatch();
        check(second.markdown().getText().toString().contains("current"), "current result not rendered");
        var last = (SpannableStringBuilder) second.markdown().getText();
        second.modal().dismiss();
        last.links.getFirst().onClick(second.markdown());
        check(Handler.QUEUE.isEmpty(), "closed sheet must not retry");
        System.out.println("Terms sheets: dismissal, URL/verification ownership and retry passed (offline fallback)");
    }
}
