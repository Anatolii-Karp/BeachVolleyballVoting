package org.example;

import static spark.Spark.*;
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

    // Map of pollId -> PollData; each poll has its own vote data (vote counts and user votes)
    private final ConcurrentHashMap<Integer, PollData> polls = new ConcurrentHashMap<>();

    // Map of pollId -> Telegram poll message ID (for updating the correct message)
    private final ConcurrentHashMap<Integer, Integer> activePollMessages = new ConcurrentHashMap<>();

    // Variable to store today's date (escaped for the main message)
    private volatile String todayDateEscaped = "";

    // Global poll ID. Each new poll increments this value, to uniquely identify polls.
    private volatile int currentPollId = 0;

    // Executor for asynchronous processing and a scheduler for daily poll scheduling.
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Constructor – schedules the daily poll.
    public TelegramVoteBot() {
        scheduleDailyPoll();
    }

    /**
     * Utility function to escape special characters for Telegram MarkdownV2 formatting.
     * (Used for parts of the text that require MarkdownV2, e.g., the date or time header.)
     */
    public static String escapeMarkdownV2(String text) {
        if (text == null) return "";
        String specialChars = "_*[]{}()~`>#-=|.—!+";
        return text.replaceAll("([" + Pattern.quote(specialChars) + "])", "\\\\$1");
    }

    /**
     * Generates an inline keyboard for a given poll using the PollData.
     * Each button's callback data is formatted as "pollId:option".
     */
    public InlineKeyboardMarkup generateKeyboard(int pollId) {
        PollData pollData = polls.get(pollId);
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        // Create a row for each time option button.
        for (Map.Entry<String, AtomicInteger> entry : pollData.getVoteCounts().entrySet()) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            String option = entry.getKey(); // Raw option string.
            int count = entry.getValue().get();
            String buttonText = option + " (" + count + " votes)";
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(buttonText);
            // Embed pollId into callback data.
            button.setCallbackData(pollId + ":" + option);
            row.add(button);
            keyboard.add(row);
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        return markup;
    }

    /**
     * Sends a new daily poll message asynchronously.
     * Creates a new poll (increments pollId), creates new PollData, and stores the message ID.
     */
    public void sendDailyPoll() {
        executor.submit(() -> {
            try {
                // Increment pollId.
                currentPollId++;
                // Create new PollData for current poll.
                PollData pollData = new PollData(timeOptions);
                polls.put(currentPollId, pollData);

                // Get the current date in Kyiv timezone and format it.
                ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                String today = now.format(formatter);
                todayDateEscaped = escapeMarkdownV2(today);

                // Compose the poll message header.
                String pollMessage = "🏖🏐 " + todayDateEscaped + " 🏐🏖\n\nSelect a time for voting\\!";

                // Build the SendMessage with MarkdownV2 parsing and the inline keyboard.
                SendMessage message = new SendMessage();
                message.setChatId(GROUP_ID);
                message.setText(pollMessage);
                message.setParseMode("MarkdownV2");
                message.setReplyMarkup(generateKeyboard(currentPollId));

                // Send the poll message and store its message ID.
                Message sentMessage = execute(message);
                activePollMessages.put(currentPollId, sentMessage.getMessageId());
                logger.info("Daily poll sent successfully with poll ID " + currentPollId);
            } catch (TelegramApiException e) {
                logger.log(Level.SEVERE, "Error in sendDailyPoll: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Schedules the daily poll at a fixed time using a ScheduledExecutorService.
     */
    private void scheduleDailyPoll() {
        ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
        // Scheduled time adjusted as needed (e.g., 19:09 Kyiv time); here set to 16:47 as example.
        ZonedDateTime scheduledTime = now.withHour(16).withMinute(47).withSecond(0).withNano(0);
        if (now.compareTo(scheduledTime) > 0) {
            scheduledTime = scheduledTime.plusDays(1);
        }
        long initialDelay = Duration.between(now, scheduledTime).getSeconds();
        long period = 24 * 60 * 60; // 24 hours in seconds.
        scheduler.scheduleAtFixedRate(this::sendDailyPoll, initialDelay, period, TimeUnit.SECONDS);
        logger.info("Scheduler set for daily poll. Initial delay: " + initialDelay + " seconds.");
    }

    /**
     * Handles the /vote command by sending a new voting message.
     * Creates a new poll by incrementing the poll ID, initializing PollData, and storing the message ID.
     */
    private void startCommand(Message message) {
        executor.submit(() -> {
            try {
                // Increment pollId and create new PollData.
                currentPollId++;
                PollData pollData = new PollData(timeOptions);
                polls.put(currentPollId, pollData);

                // Get the current date in Kyiv timezone and format it.
                ZonedDateTime now = ZonedDateTime.now(KYIV_ZONE);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
                String today = now.format(formatter);
                todayDateEscaped = escapeMarkdownV2(today);

                String text = "🏖🏐 " + todayDateEscaped + " 🏐🏖";
                SendMessage sendMessage = new SendMessage();
                sendMessage.setChatId(GROUP_ID);
                sendMessage.setText(text);
                sendMessage.setParseMode("MarkdownV2");
                sendMessage.setReplyMarkup(generateKeyboard(currentPollId));

                // Send the poll message and store its message ID.
                Message sentMsg = execute(sendMessage);
                activePollMessages.put(currentPollId, sentMsg.getMessageId());
                logger.info("Vote command executed with poll ID " + currentPollId);
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
     * Updates the poll message to display votes for a specific poll (by pollId).
     * Only the poll message corresponding to the given pollId is updated.
     */
    private void updatePollMessage(int pollId) throws TelegramApiException {
        // Get PollData for the specified pollId.
        PollData pollData = polls.get(pollId);
        StringBuilder builder = new StringBuilder();

        // For each time option, collect the mentions for that option.
        for (String option : timeOptions) {
            List<String> mentions = pollData.getUserVotes().values().stream()
                    .filter(v -> v.getVote().equals(option))
                    .map(VoteData::getMention)
                    .collect(Collectors.toList());
            if (!mentions.isEmpty()) {
                String header;
                String groupContent;
                if (option.equals("\uD83D\uDC41\uFE0F")) {
                    // For the "eye" option, use the corresponding emoji.
                    header = "\uD83D\uDC41\uFE0F";
                    groupContent = String.join(", ", mentions);
                } else if (option.equals("—")) {
                    header = option;
                    groupContent = String.join(", ", mentions);
                } else {
                    header = escapeMarkdownV2(option);
                    groupContent = String.join("\n", mentions);
                }
                builder.append(header).append("\n").append(groupContent).append("\n\n");
            }
        }
        String formattedMessage = "🏖🏐 " + todayDateEscaped + " 🏐🏖\n\n" + builder.toString().trim();
        Integer pollMsgId = activePollMessages.get(pollId);
        if (pollMsgId != null) {
            EditMessageText editMessage = new EditMessageText();
            editMessage.setChatId(GROUP_ID);
            editMessage.setMessageId(pollMsgId);
            editMessage.setText(formattedMessage);
            editMessage.setParseMode("MarkdownV2");
            // Set the inline keyboard for this specific poll.
            editMessage.setReplyMarkup(generateKeyboard(pollId));
            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                    logger.info("Message not modified, update skipped for poll ID " + pollId);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Handles callback queries when a user clicks an inline button.
     * The callback data is expected in the format "pollId:option".
     * This method updates the PollData for the respective poll.
     */
    private void handleCallbackQuery(CallbackQuery query) {
        executor.submit(() -> {
            try {
                String data = query.getData();
                String[] parts = data.split(":", 2);
                if (parts.length < 2) {
                    AnswerCallbackQuery answer = new AnswerCallbackQuery();
                    answer.setCallbackQueryId(query.getId());
                    answer.setText("Invalid callback data, please try again.");
                    execute(answer);
                    return;
                }
                int pollIdFromCallback = Integer.parseInt(parts[0]);
                String selectedTime = parts[1];

                // Get the PollData corresponding to the pollId from callback.
                PollData pollData = polls.get(pollIdFromCallback);
                if (pollData == null) {
                    AnswerCallbackQuery answer = new AnswerCallbackQuery();
                    answer.setCallbackQueryId(query.getId());
                    answer.setText("Це голосування закрите.");
                    execute(answer);
                    return;
                }

                Long userId = query.getFrom().getId();
                String firstName = query.getFrom().getFirstName();
                String lastName = query.getFrom().getLastName() != null ? query.getFrom().getLastName() : "";
                String safeName = escapeMarkdownV2(firstName + " " + lastName).trim();
                String fullName = "[" + safeName + "](tg://user?id=" + userId + ")";

                // If the user already voted in this poll, decrement the count for their previous vote.
                VoteData previousVote = pollData.getUserVotes().get(userId);
                if (previousVote != null) {
                    AtomicInteger prevCount = pollData.getVoteCounts().get(previousVote.getVote());
                    if (prevCount != null) {
                        prevCount.decrementAndGet();
                    }
                }
                // Record the new vote for this poll.
                pollData.getUserVotes().put(userId, new VoteData(selectedTime, fullName));
                AtomicInteger currentCount = pollData.getVoteCounts().get(selectedTime);
                if (currentCount != null) {
                    currentCount.incrementAndGet();
                }
                // Update the poll message for the specific poll.
                updatePollMessage(pollIdFromCallback);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(query.getId());
                execute(answer);
                logger.info("Vote updated by user " + userId + " for time " + selectedTime + " in poll " + pollIdFromCallback);
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
     * Handles the /get_chat_id command for debugging purposes.
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
        // Run web server for "keep-alive"
        port(8080);
        get("/", (req, res) -> "Bot is alive!");

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
 * Class for storing poll data for a single poll.
 * It contains voteCounts for each option and a mapping of user IDs to their votes.
 */
class PollData {
    // Map from option to its vote count
    private final Map<String, AtomicInteger> voteCounts = new LinkedHashMap<>();
    // Map from user ID to their VoteData
    private final ConcurrentHashMap<Long, VoteData> userVotes = new ConcurrentHashMap<>();

    // Constructor: initialize voteCounts with the provided time options.
    public PollData(List<String> timeOptions) {
        for (String option : timeOptions) {
            voteCounts.put(option, new AtomicInteger(0));
        }
    }

    public Map<String, AtomicInteger> getVoteCounts() {
        return voteCounts;
    }

    public ConcurrentHashMap<Long, VoteData> getUserVotes() {
        return userVotes;
    }
}

/**
 * Helper class to store vote data (selected option and user mention).
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