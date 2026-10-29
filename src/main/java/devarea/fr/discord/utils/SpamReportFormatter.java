package devarea.fr.discord.utils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import devarea.fr.discord.cache.ChannelCache;
import devarea.fr.discord.entities.Chan;
import discord4j.core.object.Embed;
import discord4j.core.object.MessageSnapshot;
import discord4j.core.object.PartialMessage;
import discord4j.core.object.entity.Attachment;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.PartialSticker;

/**
 * Builds the human-readable previews of the messages caught by the AntiSpamWorker.
 * <p>
 * A message is not only its text : spammers often send images, stickers, link embeds or forwarded messages
 * (message snapshots) with an empty content. Everything is rendered here so the staff can see what was sent.
 */
public class SpamReportFormatter {

    /**
     * Discord limits an embed description to 4096 characters, keep a small margin.
     */
    public static final int MAX_EMBED_DESCRIPTION = 4000;
    public static final int MAX_EMBED_FIELD = 1024;

    private static final int MAX_CONTENT_PREVIEW = 900;
    private static final int MAX_EMBED_TEXT_PREVIEW = 200;
    private static final int MAX_EMBEDS_PER_MESSAGE = 3;

    /**
     * Inserted after each backtick so the user content can never close (or open) our code blocks.
     */
    private static final String ZERO_WIDTH_SPACE = "​";

    /**
     * Markdown preview of one message, used in the embeds sent to the staff and in the sanction channel.
     */
    public static String preview(final int index, final Message message) {
        StringBuilder builder = new StringBuilder();

        builder.append("**#").append(index).append("** · <#").append(message.getChannelId().asString()).append(">")
            .append(" · <t:").append(message.getTimestamp().getEpochSecond()).append(":T>\n");

        int before = builder.length();

        appendContentPreview(builder, message.getContent());
        appendAttachmentsPreview(builder, message.getAttachments());
        appendStickersPreview(builder, message.getStickersItems());
        appendEmbedsPreview(builder, message.getEmbeds());

        for (MessageSnapshot snapshot : message.getMessageSnapshots()) {
            PartialMessage forwarded = snapshot.getMessage();
            builder.append("↪️ **Message transféré**\n");
            int beforeForwarded = builder.length();
            appendContentPreview(builder, forwarded.getContent().orElse(""));
            appendAttachmentsPreview(builder, forwarded.getAttachments());
            appendStickersPreview(builder, forwarded.getStickersItems());
            appendEmbedsPreview(builder, forwarded.getEmbeds());
            if (builder.length() == beforeForwarded)
                builder.append("*(contenu non disponible)*\n");
        }

        if (builder.length() == before)
            builder.append("*(aucun contenu visible)*\n");

        return truncate(builder.toString(), MAX_EMBED_DESCRIPTION);
    }

    /**
     * Groups the previews into pages that fit in an embed description.
     */
    public static List<String> paginate(final List<String> blocks, final int maxLength) {
        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (current.length() > 0 && current.length() + block.length() + 1 > maxLength) {
                pages.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0)
                current.append('\n');
            current.append(block);
        }

        if (current.length() > 0)
            pages.add(current.toString());

        return pages;
    }

    /**
     * Full plain text transcript of the messages, attached as a file so the evidence stays available after the
     * sanction channel is deleted.
     */
    public static String transcript(final Member member, final List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("Rapport anti-spam\n");
        builder.append("Membre : ").append(member.getUsername()).append(" (").append(member.getId().asString()).append(")\n");
        builder.append("Généré le : ").append(Instant.now()).append('\n');
        builder.append("Messages : ").append(messages.size()).append("\n");

        int index = 1;
        for (Message message : messages) {
            builder.append("\n----- Message ").append(index++).append('/').append(messages.size()).append(" -----\n");
            builder.append("Date : ").append(message.getTimestamp()).append('\n');
            builder.append("Salon : ").append(channelName(message.getChannelId().asString()))
                .append(" (").append(message.getChannelId().asString()).append(")\n");
            builder.append("ID : ").append(message.getId().asString()).append('\n');
            appendTranscript(builder, "", message.getContent(), message.getAttachments(), message.getStickersItems(), message.getEmbeds());

            for (MessageSnapshot snapshot : message.getMessageSnapshots()) {
                PartialMessage forwarded = snapshot.getMessage();
                builder.append("Message transféré :\n");
                appendTranscript(builder, "    ", forwarded.getContent().orElse(""), forwarded.getAttachments(),
                    forwarded.getStickersItems(), forwarded.getEmbeds());
            }
        }

        return builder.toString();
    }

    /**
     * Code block that safely displays any user content, even content made of backticks.
     */
    public static String codeBlock(final String text) {
        // The line break after the opening fence avoids the first word being read as a language name.
        return "```\n" + text.replace("`", "`" + ZERO_WIDTH_SPACE) + "\n```";
    }

    public static String truncate(final String text, final int maxLength) {
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static void appendContentPreview(final StringBuilder builder, final String content) {
        if (content == null || content.isBlank())
            return;

        String shown = content;
        String note = "";
        if (content.length() > MAX_CONTENT_PREVIEW) {
            shown = content.substring(0, MAX_CONTENT_PREVIEW) + "…";
            note = "*(tronqué, " + content.length() + " caractères au total)*\n";
        }

        builder.append(codeBlock(shown)).append('\n').append(note);
    }

    private static void appendAttachmentsPreview(final StringBuilder builder, final List<Attachment> attachments) {
        for (Attachment attachment : attachments) {
            builder.append("📎 `").append(inline(attachment.getFilename())).append("` (")
                .append(attachment.getContentType().map(type -> type + ", ").orElse(""))
                .append(humanSize(attachment.getSize())).append(") · [ouvrir](").append(attachment.getUrl()).append(")\n");
        }
    }

    private static void appendStickersPreview(final StringBuilder builder, final List<PartialSticker> stickers) {
        for (PartialSticker sticker : stickers) {
            builder.append("🏷️ Sticker : `").append(inline(sticker.getName())).append("`\n");
        }
    }

    private static void appendEmbedsPreview(final StringBuilder builder, final List<Embed> embeds) {
        int shown = 0;
        for (Embed embed : embeds) {
            Optional<String> title = embed.getTitle();
            Optional<String> url = embed.getUrl();
            Optional<String> description = embed.getDescription();

            if (title.isEmpty() && url.isEmpty() && description.isEmpty())
                continue;

            if (shown++ >= MAX_EMBEDS_PER_MESSAGE) {
                builder.append("🔗 *… et d'autres embeds*\n");
                break;
            }

            builder.append("🔗 Embed");
            // Urls are kept inside inline code so the staff can't click on a phishing link by mistake.
            title.ifPresent(value -> builder.append(" : **").append(inline(truncate(value, MAX_EMBED_TEXT_PREVIEW))).append("**"));
            url.ifPresent(value -> builder.append(" `").append(inline(value)).append('`'));
            builder.append('\n');
            description.ifPresent(value -> builder.append("> ").append(inline(truncate(value, MAX_EMBED_TEXT_PREVIEW)).replace("\n", "\n> ")).append('\n'));
        }
    }

    private static void appendTranscript(final StringBuilder builder, final String indent, final String content,
                                         final List<Attachment> attachments, final List<PartialSticker> stickers,
                                         final List<Embed> embeds) {
        if (content != null && !content.isEmpty()) {
            builder.append(indent).append("Contenu :\n");
            for (String line : content.split("\n", -1)) {
                builder.append(indent).append("  | ").append(line).append('\n');
            }
        } else {
            builder.append(indent).append("Contenu : (vide)\n");
        }

        for (Attachment attachment : attachments) {
            builder.append(indent).append("Pièce jointe : ").append(attachment.getFilename())
                .append(" (").append(attachment.getContentType().orElse("type inconnu")).append(", ")
                .append(attachment.getSize()).append(" o) ").append(attachment.getUrl()).append('\n');
        }

        for (PartialSticker sticker : stickers) {
            builder.append(indent).append("Sticker : ").append(sticker.getName()).append(" (").append(sticker.getId().asString()).append(")\n");
        }

        for (Embed embed : embeds) {
            builder.append(indent).append("Embed :");
            embed.getTitle().ifPresent(title -> builder.append(" [").append(title).append(']'));
            embed.getUrl().ifPresent(url -> builder.append(' ').append(url));
            builder.append('\n');
            embed.getDescription().ifPresent(description -> {
                for (String line : description.split("\n", -1)) {
                    builder.append(indent).append("  | ").append(line).append('\n');
                }
            });
        }
    }

    private static String channelName(final String channelId) {
        try {
            Chan<?> channel = ChannelCache.watch(channelId);
            if (channel != null && channel.entity != null)
                return "#" + channel.entity.getName();
        } catch (Exception ignored) {
        }
        return "#inconnu";
    }

    private static String inline(final String text) {
        return text.replace("`", "'");
    }

    private static String humanSize(final int bytes) {
        if (bytes < 1024)
            return bytes + " o";
        if (bytes < 1024 * 1024)
            return String.format("%.1f Ko", bytes / 1024.0);
        return String.format("%.1f Mo", bytes / (1024.0 * 1024.0));
    }
}
