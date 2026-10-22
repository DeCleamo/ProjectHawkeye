package com.example.projecthawkeye;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MessagesActivity extends Activity {

    // ============================================================
    // CONSTANTS
    // ============================================================

    private static final String MY_ID =
            MainActivity.HAWKEYE_ID;

    // ============================================================
    // DATA
    // ============================================================

    private final List<ChatRequest> requests =
            new ArrayList<>();

    private final List<Conversation> conversations =
            new ArrayList<>();

    // ============================================================
    // UI
    // ============================================================

    private LinearLayout requestContainer;
    private LinearLayout conversationContainer;

    private TextView emptyRequestsText;
    private TextView emptyConversationsText;

    private Button newChatButton;
    private Button refreshButton;

    // ============================================================
    // ON CREATE
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(
                R.layout.activity_messages
        );

        requestContainer =
                findViewById(
                        R.id.requestContainer
                );

        conversationContainer =
                findViewById(
                        R.id.conversationContainer
                );

        emptyRequestsText =
                findViewById(
                        R.id.emptyRequestsText
                );

        emptyConversationsText =
                findViewById(
                        R.id.emptyConversationsText
                );

        newChatButton =
                findViewById(
                        R.id.newChatButton
                );

        refreshButton =
                findViewById(
                        R.id.refreshButton
                );

        // --------------------------------------------------------
        // NEW CHAT
        // --------------------------------------------------------

        newChatButton.setOnClickListener(
                v -> showNewChatDialog()
        );

        // --------------------------------------------------------
        // REFRESH
        // --------------------------------------------------------

        refreshButton.setOnClickListener(
                v -> refreshUI()
        );

        refreshUI();
    }

    // ============================================================
    // RESUME
    // ============================================================

    @Override
    protected void onResume() {

        super.onResume();

        registerReceiver();

        refreshUI();
    }

    // ============================================================
    // PAUSE
    // ============================================================

    @Override
    protected void onPause() {

        super.onPause();

        try {
            unregisterReceiver(
                    hawkeyeReceiver
            );
        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // BLE EVENT RECEIVER
    // ============================================================

    private final BroadcastReceiver hawkeyeReceiver =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {

                    if (intent == null) {
                        return;
                    }

                    String action =
                            intent.getAction();

                    if (
                            MainActivity.ACTION_CHAT_REQUEST
                                    .equals(action)
                    ) {

                        handleChatRequest(intent);

                    } else if (
                            MainActivity.ACTION_CHAT_ACCEPTED
                                    .equals(action)
                    ) {

                        handleChatAccepted(intent);

                    } else if (
                            MainActivity.ACTION_CHAT_DECLINED
                                    .equals(action)
                    ) {

                        handleChatDeclined(intent);

                    } else if (
                            MainActivity.ACTION_CHAT_MESSAGE
                                    .equals(action)
                    ) {

                        handleIncomingMessage(intent);
                    }
                }
            };

    // ============================================================
    // REGISTER RECEIVER
    // ============================================================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiver() {

        IntentFilter filter =
                new IntentFilter();

        filter.addAction(
                MainActivity.ACTION_CHAT_REQUEST
        );

        filter.addAction(
                MainActivity.ACTION_CHAT_ACCEPTED
        );

        filter.addAction(
                MainActivity.ACTION_CHAT_DECLINED
        );

        filter.addAction(
                MainActivity.ACTION_CHAT_MESSAGE
        );

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                    hawkeyeReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(
                    hawkeyeReceiver,
                    filter
            );
        }
    }

    // ============================================================
    // INCOMING CHAT REQUEST
    // ============================================================

    private void handleChatRequest(
            Intent intent
    ) {

        String requestId =
                intent.getStringExtra(
                        MainActivity.EXTRA_REQUEST_ID
                );

        String senderId =
                intent.getStringExtra(
                        MainActivity.EXTRA_SENDER_ID
                );

        String receiverId =
                intent.getStringExtra(
                        MainActivity.EXTRA_RECEIVER_ID
                );

        if (
                requestId == null ||
                        senderId == null ||
                        receiverId == null
        ) {
            return;
        }

        if (!MY_ID.equals(receiverId)) {
            return;
        }

        // Avoid duplicate request
        for (ChatRequest request : requests) {

            if (request.id.equals(requestId)) {
                return;
            }
        }

        ChatRequest request =
                new ChatRequest(
                        requestId,
                        senderId,
                        receiverId
                );

        requests.add(
                0,
                request
        );

        refreshUI();

        // --------------------------------------------------------
        // POPUP
        // --------------------------------------------------------

        new AlertDialog.Builder(this)
                .setTitle(
                        "New Chat Request"
                )
                .setMessage(
                        senderId +
                                " wants to chat with you."
                )
                .setPositiveButton(
                        "Accept",
                        (dialog, which) ->
                                acceptRequest(request)
                )
                .setNegativeButton(
                        "Decline",
                        (dialog, which) ->
                                declineRequest(request)
                )
                .show();
    }

    // ============================================================
    // ACCEPT REQUEST
    // ============================================================

    private void acceptRequest(
            ChatRequest request
    ) {

        if (request == null) {
            return;
        }

        MainActivity.acceptChatRequestFromActivity(
                this,
                request.id,
                request.senderId,
                request.receiverId
        );

        requests.remove(request);

        refreshUI();

        Toast.makeText(
                this,
                "Chat request accepted",
                Toast.LENGTH_SHORT
        ).show();
    }

    // ============================================================
    // DECLINE REQUEST
    // ============================================================

    private void declineRequest(
            ChatRequest request
    ) {

        if (request == null) {
            return;
        }

        MainActivity.declineChatRequestFromActivity(
                this,
                request.id,
                request.senderId,
                request.receiverId
        );

        requests.remove(request);

        refreshUI();

        Toast.makeText(
                this,
                "Chat request declined",
                Toast.LENGTH_SHORT
        ).show();
    }

    // ============================================================
    // INCOMING CHAT ACCEPTED
    // ============================================================

    private void handleChatAccepted(
            Intent intent
    ) {

        String conversationId =
                intent.getStringExtra(
                        MainActivity.EXTRA_CONVERSATION_ID
                );

        String senderId =
                intent.getStringExtra(
                        MainActivity.EXTRA_SENDER_ID
                );

        String receiverId =
                intent.getStringExtra(
                        MainActivity.EXTRA_RECEIVER_ID
                );

        if (
                conversationId == null ||
                        senderId == null ||
                        receiverId == null
        ) {
            return;
        }

        String otherUser;

        if (MY_ID.equals(senderId)) {

            otherUser = receiverId;

        } else {

            otherUser = senderId;
        }

        addConversation(
                conversationId,
                otherUser
        );

        refreshUI();

        Toast.makeText(
                this,
                "Chat active with " +
                        otherUser,
                Toast.LENGTH_SHORT
        ).show();
    }

    // ============================================================
    // INCOMING CHAT DECLINED
    // ============================================================

    private void handleChatDeclined(
            Intent intent
    ) {

        String senderId =
                intent.getStringExtra(
                        MainActivity.EXTRA_SENDER_ID
                );

        String receiverId =
                intent.getStringExtra(
                        MainActivity.EXTRA_RECEIVER_ID
                );

        if (
                senderId == null ||
                        receiverId == null
        ) {
            return;
        }

        String otherUser;

        if (MY_ID.equals(senderId)) {

            otherUser = receiverId;

        } else {

            otherUser = senderId;
        }

        Toast.makeText(
                this,
                otherUser +
                        " declined the chat request",
                Toast.LENGTH_LONG
        ).show();
    }

    // ============================================================
    // INCOMING MESSAGE
    // ============================================================

    private void handleIncomingMessage(
            Intent intent
    ) {

        String senderId =
                intent.getStringExtra(
                        MainActivity.EXTRA_SENDER_ID
                );

        String message =
                intent.getStringExtra(
                        MainActivity.EXTRA_MESSAGE
                );

        if (
                senderId == null ||
                        message == null
        ) {
            return;
        }

        Toast.makeText(
                this,
                senderId +
                        ": " +
                        message,
                Toast.LENGTH_LONG
        ).show();
    }

    // ============================================================
    // NEW CHAT DIALOG
    // ============================================================

    private void showNewChatDialog() {

        final EditText input =
                new EditText(this);

        input.setHint(
                "Example: HWK-U002"
        );

        input.setSingleLine(true);

        int padding = 40;

        LinearLayout container =
                new LinearLayout(this);

        container.setPadding(
                padding,
                0,
                padding,
                0
        );

        container.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        new AlertDialog.Builder(this)
                .setTitle(
                        "Start New Chat"
                )
                .setMessage(
                        "Enter the Hawkeye ID of the tourist."
                )
                .setView(container)
                .setPositiveButton(
                        "SEND REQUEST",
                        (dialog, which) -> {

                            String receiverId =
                                    input.getText()
                                            .toString()
                                            .trim();

                            sendRequest(
                                    receiverId
                            );
                        }
                )
                .setNegativeButton(
                        "CANCEL",
                        null
                )
                .show();
    }

    // ============================================================
    // SEND REQUEST
    // ============================================================

    private void sendRequest(
            String receiverId
    ) {

        if (
                receiverId == null ||
                        receiverId.isEmpty()
        ) {

            Toast.makeText(
                    this,
                    "Enter a tourist ID",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        if (
                MY_ID.equals(
                        receiverId
                )
        ) {

            Toast.makeText(
                    this,
                    "You cannot chat with yourself",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        MainActivity.sendChatRequestFromActivity(
                this,
                receiverId
        );

        Toast.makeText(
                this,
                "Request sent to " +
                        receiverId,
                Toast.LENGTH_SHORT
        ).show();
    }

    // ============================================================
    // ADD CONVERSATION
    // ============================================================

    private void addConversation(
            String conversationId,
            String otherUser
    ) {

        for (
                Conversation conversation :
                conversations
        ) {

            if (
                    conversation.id.equals(
                            conversationId
                    )
            ) {
                return;
            }
        }

        conversations.add(
                0,
                new Conversation(
                        conversationId,
                        otherUser
                )
        );
    }

    // ============================================================
    // OPEN CHAT
    // ============================================================

    private void openChat(
            Conversation conversation
    ) {

        Intent intent =
                new Intent(
                        this,
                        ChatActivity.class
                );

        intent.putExtra(
                "conversation_id",
                conversation.id
        );

        intent.putExtra(
                "other_user",
                conversation.otherUser
        );

        startActivity(intent);
    }

    // ============================================================
    // REFRESH UI
    // ============================================================

    private void refreshUI() {

        renderRequests();

        renderConversations();
    }

    // ============================================================
    // RENDER REQUESTS
    // ============================================================

    private void renderRequests() {

        requestContainer.removeAllViews();

        if (requests.isEmpty()) {

            emptyRequestsText.setVisibility(
                    View.VISIBLE
            );

            return;
        }

        emptyRequestsText.setVisibility(
                View.GONE
        );

        for (ChatRequest request :
                requests) {

            requestContainer.addView(
                    createRequestCard(
                            request
                    )
            );
        }
    }

    // ============================================================
    // REQUEST CARD
    // ============================================================

    private View createRequestCard(
            ChatRequest request
    ) {

        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                30,
                24,
                30,
                24
        );

        TextView title =
                new TextView(this);

        title.setText(
                request.senderId
        );

        title.setTextSize(19);

        TextView description =
                new TextView(this);

        description.setText(
                "Wants to start a conversation"
        );

        description.setTextSize(14);

        LinearLayout buttons =
                new LinearLayout(this);

        buttons.setGravity(
                Gravity.END
        );

        Button accept =
                new Button(this);

        accept.setText(
                "ACCEPT"
        );

        Button decline =
                new Button(this);

        decline.setText(
                "DECLINE"
        );

        accept.setOnClickListener(
                v -> acceptRequest(request)
        );

        decline.setOnClickListener(
                v -> declineRequest(request)
        );

        buttons.addView(accept);
        buttons.addView(decline);

        card.addView(title);
        card.addView(description);
        card.addView(buttons);

        return card;
    }

    // ============================================================
    // RENDER CONVERSATIONS
    // ============================================================

    private void renderConversations() {

        conversationContainer.removeAllViews();

        if (conversations.isEmpty()) {

            emptyConversationsText.setVisibility(
                    View.VISIBLE
            );

            return;
        }

        emptyConversationsText.setVisibility(
                View.GONE
        );

        for (
                Conversation conversation :
                conversations
        ) {

            conversationContainer.addView(
                    createConversationCard(
                            conversation
                    )
            );
        }
    }

    // ============================================================
    // CONVERSATION CARD
    // ============================================================

    private View createConversationCard(
            Conversation conversation
    ) {

        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                30,
                28,
                30,
                28
        );

        TextView title =
                new TextView(this);

        title.setText(
                conversation.otherUser
        );

        title.setTextSize(19);

        TextView status =
                new TextView(this);

        status.setText(
                "Chat active  •  Tap to open"
        );

        status.setTextSize(14);

        card.addView(title);
        card.addView(status);

        card.setOnClickListener(
                v -> openChat(conversation)
        );

        return card;
    }

    // ============================================================
    // DATA OBJECTS
    // ============================================================

    private static class ChatRequest {

        String id;
        String senderId;
        String receiverId;

        ChatRequest(
                String id,
                String senderId,
                String receiverId
        ) {

            this.id = id;
            this.senderId = senderId;
            this.receiverId = receiverId;
        }
    }

    private static class Conversation {

        String id;
        String otherUser;

        Conversation(
                String id,
                String otherUser
        ) {

            this.id = id;
            this.otherUser = otherUser;
        }
    }
}