package devarea.fr.discord.entities.sub_entities;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import discord4j.core.object.Embed;
import discord4j.core.object.MessageSnapshot;
import discord4j.core.object.PartialMessage;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Message;

public class MemberBehavior {
    /* Represents the behavior a member, used in AntiSpamWorker */

    /**
     * Time in millis.
     */
    private static final long forgetTime = 10000;
    private static final Map<String, Integer> suspectWords = Map.ofEntries(
        Map.entry("nude", 30),
        Map.entry("porn", 30),
        Map.entry("onlyfans", 30),
        Map.entry("hentai", 20),
        Map.entry("free nitro", 30),
        Map.entry("nitro gratuit", 30),
        Map.entry("steam gift", 20),
        Map.entry("airdrop", 15),
        Map.entry("18+", 15),
        Map.entry("@everyone", 20),
        Map.entry("@here", 20)
    );

    private static final Pattern invitePattern = Pattern.compile("(discord(app)?\\.(gg|com/invite|me)|dsc\\.gg|discord\\.io|invite\\.gg)/\\S+");
    private static final Pattern linkPattern = Pattern.compile("https?://\\S+");
    /**
     * Invisible characters used to bypass the detection (ex: "discord.gg/​abc").
     */
    private static final Pattern invisibleCharacters = Pattern.compile("[\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF]");
    /**
     * Messages shorter than that are not considered for duplicate detection ("ok", "merci"...).
     */
    private static final int minDuplicateLength = 10;

    public int messageStreak = 0;
    public ArrayList<Message> lastMessages = new ArrayList<>();
    public Set<String> channelsId = new HashSet<>(); // IDs of recents channels user sent messages to
    public int invitesCount = 0;
    public int linksCount = 0;
    public int mentionsCount = 0;
    public int duplicatesCount = 0;
    public int forwardedCount = 0;
    public int attachmentsCount = 0;
    public int suspectWordsScore = 0;
    public boolean flagged = false;

    private final Set<String> messageFingerprints = new HashSet<>();

    private long firstRecordTimestamp = 0;
    private long lastRecordTimestamp = 0;

    public MemberBehavior() {
    }

    /**
     * Forget the recorded messages. Do not reset {@link #flagged} : a flagged member stays flagged until the staff
     * takes a decision (the behavior is then removed from the member).
     */
    public synchronized void forgetBehavior() {
        messageStreak = 0;
        lastMessages.clear();
        channelsId.clear();
        invitesCount = 0;
        linksCount = 0;
        mentionsCount = 0;
        duplicatesCount = 0;
        forwardedCount = 0;
        attachmentsCount = 0;
        suspectWordsScore = 0;
        messageFingerprints.clear();
    }

    public synchronized void recordMessage(final Message message) {
        if (canBeForgiven()) {
            forgetBehavior();
            firstRecordTimestamp = System.currentTimeMillis();
        }

        lastRecordTimestamp = System.currentTimeMillis();

        messageStreak += 1;
        lastMessages.add(message);
        channelsId.add(message.getChannelId().asString());

        // Forwarded messages have an empty content, the spam is inside the snapshots.
        StringBuilder rawContent = new StringBuilder(message.getContent());
        List<Attachment> attachments = new ArrayList<>(message.getAttachments());
        appendEmbedsText(rawContent, message.getEmbeds());
        for (MessageSnapshot snapshot : message.getMessageSnapshots()) {
            PartialMessage forwarded = snapshot.getMessage();
            forwardedCount += 1;
            rawContent.append('\n').append(forwarded.getContent().orElse(""));
            appendEmbedsText(rawContent, forwarded.getEmbeds());
            attachments.addAll(forwarded.getAttachments());
        }
        attachmentsCount += attachments.size();

        String messageContent = normalize(rawContent.toString());
        String deobfuscatedContent = deobfuscate(messageContent);

        /* Invite link */
        Matcher inviteMatcher = invitePattern.matcher(messageContent);
        while (inviteMatcher.find()) {
            invitesCount += 1;
        }

        /* Other links */
        Matcher linkMatcher = linkPattern.matcher(messageContent);
        while (linkMatcher.find()) {
            if (!invitePattern.matcher(linkMatcher.group()).find())
                linksCount += 1;
        }

        /* Mass mention */
        Set<String> mentioned = new HashSet<>();
        message.getUserMentionIds().forEach(id -> mentioned.add(id.asString()));
        message.getRoleMentionIds().forEach(id -> mentioned.add(id.asString()));
        mentionsCount += mentioned.size();

        for (Map.Entry<String, Integer> suspectWord : suspectWords.entrySet()) {
            if (messageContent.contains(suspectWord.getKey()) || deobfuscatedContent.contains(suspectWord.getKey())) {
                suspectWordsScore += suspectWord.getValue();
            }
        }

        /* Same message sent multiple times (often in several channels) */
        StringBuilder fingerprint = new StringBuilder(messageContent.replaceAll("\\s+", " ").trim());
        for (Attachment attachment : attachments) {
            fingerprint.append("|").append(attachment.getFilename()).append(":").append(attachment.getSize());
        }
        if ((fingerprint.length() >= minDuplicateLength || !attachments.isEmpty())
            && !messageFingerprints.add(fingerprint.toString())) {
            duplicatesCount += 1;
        }
    }

    // TODO : adjust these parameters
    /* > 60 means dangerous, > 30 means suspect, < 30 is ok */
    public synchronized int getBehaviorScore() {
        return messageStreak * 4
            + channelsId.size() * 10
            + invitesCount * 20
            + linksCount * 5
            + mentionsCount * 3
            + duplicatesCount * 15
            + forwardedCount * 5
            + suspectWordsScore;
    }

    /**
     * @return true if the member was not flagged yet, so the anti spam procedure is started only once.
     */
    public synchronized boolean flag() {
        if (flagged)
            return false;
        flagged = true;
        return true;
    }

    public synchronized List<Message> getLastMessages() {
        return new ArrayList<>(lastMessages);
    }

    public long getRecordDuration() {
        return System.currentTimeMillis() - firstRecordTimestamp;
    }

    public boolean canBeForgiven() {
        return System.currentTimeMillis() - lastRecordTimestamp > forgetTime;
    }

    /**
     * Lower case, without invisible characters, and with "fancy" unicode letters (𝓛𝔂𝓷𝓽𝓪𝓴𝓼, ｆｕｌｌｗｉｄｔｈ...)
     * converted to their basic form.
     */
    private static String normalize(final String content) {
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC);
        return invisibleCharacters.matcher(normalized).replaceAll("").toLowerCase();
    }

    private static String deobfuscate(final String content) {
        return content.replace("0", "o").replace("1", "i").replace("3", "e").replace("4", "a").replace("5", "s")
            .replace("$", "s");
    }

    private static void appendEmbedsText(final StringBuilder builder, final List<Embed> embeds) {
        for (Embed embed : embeds) {
            embed.getTitle().ifPresent(title -> builder.append('\n').append(title));
            embed.getDescription().ifPresent(description -> builder.append('\n').append(description));
        }
    }
}
