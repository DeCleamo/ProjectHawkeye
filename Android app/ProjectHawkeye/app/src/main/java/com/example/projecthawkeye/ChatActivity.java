package com.example.projecthawkeye;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class ChatActivity extends Activity {

    // ============================================================
    // DATA
    // ============================================================

    private int conversationId;

    private String otherUser;


    // ============================================================
    // UI
    // ============================================================

    private TextView chatTitle;

    private TextView chatSubtitle;

    private LinearLayout messageContainer;

    private ScrollView messageScroll;

    private EditText messageInput;

    private Button sendButton;


    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_chat
        );


        // ========================================================
        // GET INTENT DATA
        // ========================================================

        conversationId =
                getIntent().getIntExtra(
                        "conversation_id",
                        -1
                );


        otherUser =
                getIntent().getStringExtra(
                        "other_user"
                );


        if (otherUser == null) {

            otherUser = "Tourist";
        }


        // ========================================================
        // UI
        // ========================================================

        chatTitle =
                findViewById(
                        R.id.chatTitle
                );


        chatSubtitle =
                findViewById(
                        R.id.chatSubtitle
                );


        messageContainer =
                findViewById(
                        R.id.messageContainer
                );


        messageScroll =
                findViewById(
                        R.id.messageScroll
                );


        messageInput =
                findViewById(
                        R.id.messageInput
                );


        sendButton =
                findViewById(
                        R.id.sendButton
                );


        // ========================================================
        // HEADER
        // ========================================================

        chatTitle.setText(
                "Chat with " +
                        otherUser
        );


        chatSubtitle.setText(
                "Conversation #" +
                        conversationId
        );


        // ========================================================
        // SEND
        // ========================================================

        sendButton.setOnClickListener(
                v -> sendMessage()
        );


        addSystemMessage(
                "Secure Hawkeye conversation started."
        );
    }


    // ============================================================
    // SEND MESSAGE
    // ============================================================

    private void sendMessage() {

        String message =
                messageInput
                        .getText()
                        .toString()
                        .trim();


        if (message.isEmpty()) {

            return;
        }


        /*
         * Send through MainActivity's existing BLE
         * communication layer.
         */



        /*
         * Immediately display our own message.
         *
         * Server persistence will happen through the
         * Hawkeye communication path.
         */
        addOutgoingMessage(
                message
        );


        messageInput.setText("");


        scrollToBottom();
    }


    // ============================================================
    // DISPLAY OUTGOING
    // ============================================================

    private void addOutgoingMessage(
            String message
    ) {

        TextView text =
                new TextView(this);


        text.setText(
                "You\n" +
                        message
        );


        text.setTextColor(
                0xFFFFFFFF
        );


        text.setTextSize(
                16
        );


        text.setPadding(
                18,
                14,
                18,
                14
        );


        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );


        params.setMargins(
                30,
                8,
                0,
                8
        );


        messageContainer.addView(
                text,
                params
        );
    }


    // ============================================================
    // DISPLAY INCOMING
    // ============================================================

    public void addIncomingMessage(
            String sender,
            String message
    ) {

        TextView text =
                new TextView(this);


        text.setText(
                sender +
                        "\n" +
                        message
        );


        text.setTextColor(
                0xFFFFFFFF
        );


        text.setTextSize(
                16
        );


        text.setPadding(
                18,
                14,
                18,
                14
        );


        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );


        params.setMargins(
                0,
                8,
                30,
                8
        );


        messageContainer.addView(
                text,
                params
        );


        scrollToBottom();
    }


    // ============================================================
    // SYSTEM MESSAGE
    // ============================================================

    private void addSystemMessage(
            String message
    ) {

        TextView text =
                new TextView(this);


        text.setText(
                message
        );


        text.setTextColor(
                0xFFAAAAAA
        );


        text.setTextSize(
                13
        );


        text.setPadding(
                10,
                10,
                10,
                10
        );


        messageContainer.addView(
                text
        );
    }


    // ============================================================
    // SCROLL
    // ============================================================

    private void scrollToBottom() {

        messageScroll.post(
                () ->
                        messageScroll.fullScroll(
                                ScrollView.FOCUS_DOWN
                        )
        );
    }
}