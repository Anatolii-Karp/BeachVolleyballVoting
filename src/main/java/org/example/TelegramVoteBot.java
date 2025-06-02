package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TelegramVoteBot extends TelegramLongPollingBot {
    // Bot configuration constants
    private static final String TOKEN = System.getenv("BOT_TOKEN");
    private static final String BOT_USERNAME = "YourBotUsername"; // Replace with your bot's username
    private static final String GROUP_ID = System.getenv("GROUP_ID"); // Telegram group chat ID
    private static final ZoneId KYIV_ZONE = ZoneId.of("Europe/Kyiv");
    private static final Logger logger = Logger.getLogger(TelegramVoteBot.class.getName());

    // List of time options in desired order.
    // These options will be displayed as raw strings in the inline keyboard.
    private static final List<String> timeOptions = Arrays.asList(
            "13:00", "14:00", "15:00", "16:00", "17:00", "18:00+", "\uD83D\uDC41\uFE0F", "—"
    );

    // Use LinkedHashMap to preserve the insertion order for vote counts.
    private final Map<String, AtomicInteger> voteCounts = new LinkedHashMap<>();
    // userVotes maps user ID to their vote data (option and MarkdownV2 mention)
    private final ConcurrentHashMap<Long, VoteData> userVotes = new ConcurrentHashMap<>();

    // Variables to store today's date (escaped for the main message) and the poll message ID
    private volatile String todayDateEscaped = "";
    private volatile Integer pollMessageId = null;

    // Global poll ID. Each new poll increments this value so that votes don't mix.
    private volatile int currentPollId = 0;

    // Executor for asynchronous processing and a scheduler for daily poll scheduling
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Constructor – initialize vote counts and schedule the daily poll
    public TelegramVoteBot() {
        resetVotes();
        scheduleDailyPoll();
    }

    /**
     * Resets the vote counts and clears user votes.
     * Inserts the options in the specified order.
     */
    private void resetVotes() {
        voteCounts.clear();
        userVotes.clear();
        for (String time : timeOptions) {
            // Insert the raw time string for display purposes (no escaping here)
            voteCounts.put(time, new AtomicInteger(0));
        }
    }

    /**
     * Utility function to escape special characters for Telegram MarkdownV2 formatting.
     * Use this only for parts of the text that require MarkdownV2 (e.g., the date or time header).
     */
    public static String escapeMarkdownV2(String text) {
        if (text == null) return "";
        String specialChars = "_*[]{}()~`>#-=|.—!+";
        return text.replaceAll("([" + Pattern.quote(specialChars) + "])", "\\\\$1");
    }

    /**
     * Generates an inline keyboard with a button for each time option.
     * Each button's callback data is formatted as "pollId:option".
     */
    public InlineKeyboardMarkup generateKeyboard() {
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        // Create a row for each time option button
        for (Map.Entry<String, AtomicInteger> entry : voteCounts.entrySet()) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            String option = entry.getKey(); // raw time string (e.g., "18:00+" or "—")
            int count = entry.getValue().get();
            String buttonText = option + " (" + count + " votes)";
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(buttonText); // use raw text (no extra escaping)
            // Embed the current poll ID in the callback data
            button.setCallbackData(currentPollId + ":" + option);
            row.add(button);
            keyboard.add(row);
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    /**
     * Sends the daily poll message asynchronously to the Telegram group.
     * Creates a new poll (by incrementing the poll ID), resets votes, and sends the poll header.
     */
    public void sendDailyPoll() {
        executor.submit(() -> {
            try {
                // Reset votes for the new poll and increment the poll ID
                resetVotes();
                currentPollId++;
                // Get the current date in Kyiv timezone and format it
                ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                String today = now.format(formatter);
                // Escape the date for MarkdownV2
                todayDateEscaped = escapeMarkdownV2(today);

                // Compose the poll message header
                String pollMessage = "🏖🏐 " + todayDateEscaped + " 🏐🏖\n\nSelect a time for voting\\!";

                // Build the SendMessage with MarkdownV2 parsing and the inline keyboard
                SendMessage message = new SendMessage();
                message.setChatId(GROUP_ID);
                message.setText(pollMessage);
                message.setParseMode("MarkdownV2");
                message.setReplyMarkup(generateKeyboard());

                // Send the message and store its message ID for subsequent updates
                Message sentMessage = execute(message);
                pollMessageId = sentMessage.getMessageId();
                logger.info("Daily poll sent successfully (Long Polling mode) with poll ID " + currentPollId);
            } catch (TelegramApiException e) {
                logger.log(Level.SEVERE, "Error in sendDailyPoll: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Schedules the daily poll at a fixed time (19:09 Kyiv time)
     * using a ScheduledExecutorService.
     */
    private void scheduleDailyPoll() {
        ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
        ZonedDateTime scheduledTime = now.withHour(16).withMinute(47).withSecond(0).withNano(0);
        if (now.compareTo(scheduledTime) > 0) {
            scheduledTime = scheduledTime.plusDays(1);
        }
        long initialDelay = Duration.between(now, scheduledTime).getSeconds();
        long period = 24 * 60 * 60; // 24 hours in seconds

        scheduler.scheduleAtFixedRate(this::sendDailyPoll, initialDelay, period, TimeUnit.SECONDS);
        logger.info("Scheduler set for daily poll at 19:09 Kyiv time. Initial delay: " + initialDelay + " seconds.");
    }

    /**
     * Handles the /vote command by sending a new voting message.
     * This creates a new poll by resetting votes and incrementing the poll ID.
     */
    private void startCommand(Message message) {
        executor.submit(() -> {
            try {
                // Get the current date in Kyiv timezone and escape it for MarkdownV2
                ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                String today = now.format(formatter);
                todayDateEscaped = escapeMarkdownV2(today);

                // Create a new poll by resetting votes and incrementing the poll ID
                resetVotes();
                currentPollId++;

                String text = "🏖🏐 " + todayDateEscaped + " 🏐🏖";
                SendMessage sendMessage = new SendMessage();
                sendMessage.setChatId(GROUP_ID);
                sendMessage.setText(text);
                sendMessage.setParseMode("MarkdownV2");
                sendMessage.setReplyMarkup(generateKeyboard());

                Message sentMsg = execute(sendMessage);
                pollMessageId = sentMsg.getMessageId();
                logger.info("Vote command executed in chat " + message.getChatId() + " with poll ID " + currentPollId);
            } catch (TelegramApiException e) {
                logger.log(Level.SEVERE, "Error in startCommand: " + e.getMessage(), e);
                try {
                    SendMessage errorMsg = new SendMessage();
                    errorMsg.setChatId(message.getChatId().toString());
                    errorMsg.setText("An error occurred. Please try again.");
                    execute(errorMsg);
                } catch (TelegramApiException ex) {
                    logger.log(Level.SEVERE, "Error sending error message: " + ex.getMessage(), ex);
                }
            }
        });
    }

    /**
     * Updates the poll message to display votes grouped by time option.
     * For each time option with at least one vote, the message shows:
     * - For typical time options (e.g. "13:00", "14:00", etc.): the header (escaped)
     * on one line, followed by each user mention on a new line.
     * - For the eye option (👁️): the header is replaced with "👁️" and all user mentions are
     * joined by commas.
     * - For the dash option ("—"): the header remains, and user mentions are joined by commas.
     * If no user voted for an option, that option is omitted from the message.
     */
    private void updatePollMessage() throws TelegramApiException {
        StringBuilder builder = new StringBuilder();

        for (String option : timeOptions) {
            List<String> mentions = userVotes.values().stream()
                    .filter(v -> v.getVote().equals(option))
                    .map(VoteData::getMention)
                    .collect(Collectors.toList());
            if (!mentions.isEmpty()) {
                String header;
                String groupContent;
                if (option.equals("\uD83D\uDC41\uFE0F")) {
                    // For the eye option, replace header with "👁️" and join mentions with commas
                    header = "\uD83D\uDC41\uFE0F";
                    groupContent = String.join(", ", mentions);
                } else if (option.equals("—")) {
                    // For dash option, header remains and join mentions with commas
                    header = option;
                    groupContent = String.join(", ", mentions);
                } else {
                    // For other time options, header is escaped and each user on a separate line
                    header = escapeMarkdownV2(option);
                    groupContent = String.join("\n", mentions);
                }
                builder.append(header).append("\n").append(groupContent).append("\n\n");
            }
        }
        String formattedMessage = "🏖🏐 " + todayDateEscaped + " 🏐🏖\n\n" + builder.toString().trim();

        if (pollMessageId != null) {
            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(GROUP_ID);
            editMessage.setMessageId(pollMessageId);
            editMessage.setText(formattedMessage);
            editMessage.setParseMode("MarkdownV2");
            // Use the keyboard without any toggle button (buttons now include poll ID)
            editMessage.setReplyMarkup(generateKeyboard());
            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                    logger.info("Message not modified, update skipped.");
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Handles callback queries when a user clicks an inline button.
     * All toggle logic has been removed; only vote selection is processed.
     * The callback data is expected in the format "pollId:option".
     * If the pollId from the callback does not match the current poll,
     * the vote is rejected (with an appropriate callback answer).
     */
    private void handleCallbackQuery(CallbackQuery query) {
        executor.submit(() -> {
            try {
                String data = query.getData();
                String[] parts = data.split(":", 2);
                if (parts.length < 2) {
                    // Invalid callback data; reply accordingly.
                    AnswerCallbackQuery answer = new AnswerCallbackQuery();
                    answer.setCallbackQueryId(query.getId());
                    answer.setText("Invalid callback data, please try again.");
                    execute(answer);
                    return;
                }
                int pollIdFromCallback = Integer.parseInt(parts[0]);
                String selectedTime = parts[1];
                // Check that the callback pollId matches the current poll_id
                if (pollIdFromCallback != currentPollId) {
                    AnswerCallbackQuery answer = new AnswerCallbackQuery();
                    answer.setCallbackQueryId(query.getId());
                    answer.setText("Це голосування закрите.");
                    execute(answer);
                    return;
                }
                Long userId = query.getFrom().getId();
                String firstName = query.getFrom().getFirstName();
                String lastName = query.getFrom().getLastName() != null ? query.getFrom().getLastName() : "";

                // Create a MarkdownV2 mention for the user
                String safeName = escapeMarkdownV2(firstName + " " + lastName).trim();
                String fullName = "[" + safeName + "](tg://user?id=" + userId + ")";

                // Update vote counts atomically: if the user has previously voted, decrement that vote.
                VoteData previousVote = userVotes.get(userId);
                if (previousVote != null) {
                    AtomicInteger prevCount = voteCounts.get(previousVote.getVote());
                    if (prevCount != null) {
                        prevCount.decrementAndGet();
                    }
                }
                // Store the new vote and increment its count.
                userVotes.put(userId, new VoteData(selectedTime, fullName));
                AtomicInteger currentCount = voteCounts.get(selectedTime);
                if (currentCount != null) {
                    currentCount.incrementAndGet();
                }

                // Update the poll message with the new vote counts and grouping.
                updatePollMessage();

                // Answer the callback query to remove the loading animation.
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(query.getId());
                execute(answer);
                logger.info("Vote updated by user " + userId + " for time " + selectedTime);
            } catch (TelegramApiException e) {
                logger.log(Level.SEVERE, "Error in handleCallbackQuery: " + e.getMessage(), e);
                try {
                    AnswerCallbackQuery answer = new AnswerCallbackQuery();
                    answer.setCallbackQueryId(query.getId());
                    answer.setText("An error occurred. Please try again.");
                    execute(answer);
                } catch (TelegramApiException ex) {
                    logger.log(Level.SEVERE, "Error answering callback query: " + ex.getMessage(), ex);
                }
            }
        });
    }

    /**
     * Handles the /get_chat_id command to return the chat ID for debugging.
     */
    private void handleGetChatId(Message message) {
        executor.submit(() -> {
            try {
                Long chatId = message.getChatId();
                SendMessage reply = new SendMessage();
                reply.setChatId(chatId.toString());
                reply.setText("Chat ID: " + chatId);
                execute(reply);
                logger.info("Chat ID requested: " + chatId);
            } catch (TelegramApiException e) {
                logger.log(Level.SEVERE, "Error in handleGetChatId: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Main update handler that offloads incoming updates to an asynchronous executor.
     */
    @Override
    public void onUpdateReceived(Update update) {
        executor.submit(() -> processUpdate(update));
    }

    /**
     * Processes incoming updates by dispatching commands or callback queries.
     */
    private void processUpdate(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                Message message = update.getMessage();
                String text = message.getText();
                if (text.startsWith("/vote")) {
                    startCommand(message);
                } else if (text.startsWith("/get_chat_id")) {
                    handleGetChatId(message);
                }
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing update: " + e.getMessage(), e);
        }
    }

    // Methods required by TelegramLongPollingBot
    @Override
    public String getBotUsername() {
        return BOT_USERNAME;
    }

    @Override
    public String getBotToken() {
        return TOKEN;
    }

    /**
     * Main method to register and start the long polling bot.
     */
    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new TelegramVoteBot());
            logger.info("Long Polling Bot Started");
        } catch (TelegramApiException e) {
            logger.log(Level.SEVERE, "Error starting long polling bot: " + e.getMessage(), e);
        }
    }
}

/**
 * Helper class to store vote data (selected time and user mention).
 */
class VoteData {
    private final String vote;
    private final String mention;

    public VoteData(String vote, String mention) {
        this.vote = vote;
        this.mention = mention;
    }

    public String getVote() {
        return vote;
    }

    public String getMention() {
        return mention;
    }
}
