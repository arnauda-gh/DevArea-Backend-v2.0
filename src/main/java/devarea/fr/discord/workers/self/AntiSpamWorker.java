package devarea.fr.discord.workers.self;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import devarea.fr.discord.Core;
import devarea.fr.discord.cache.ChannelCache;
import devarea.fr.discord.cache.MemberCache;
import devarea.fr.discord.cache.cached_entity.CachedChannel;
import devarea.fr.discord.commands.Permissions;
import devarea.fr.discord.entities.ActionEvent;
import devarea.fr.discord.entities.Mem;
import devarea.fr.discord.entities.events_filler.MessageCreateEventFiller;
import devarea.fr.discord.entities.sub_entities.MemberBehavior;
import devarea.fr.discord.utils.SpamReportFormatter;
import devarea.fr.discord.workers.Worker;
import devarea.fr.utils.Logger;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.emoji.Emoji;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.GuildMemberEditSpec;
import discord4j.core.spec.MessageCreateFields;
import discord4j.core.spec.MessageCreateSpec;
import discord4j.core.spec.TextChannelCreateSpec;
import discord4j.rest.util.Color;
import discord4j.rest.util.Permission;
import discord4j.rest.util.PermissionSet;
import discord4j.common.util.Snowflake;
import discord4j.core.object.PermissionOverwrite;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.entity.Member;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static devarea.fr.utils.ThreadHandler.repeatEachMillis;


public class AntiSpamWorker implements Worker {

    // Used to help release behaviour.
    private static final Set<String> membersWithBehaviour = ConcurrentHashMap.newKeySet();
    // Roles of the flagged members before they got the spam role, restored when the staff releases them.
    private static final Map<String, Set<Snowflake>> savedRoles = new ConcurrentHashMap<>();
    private static final Permissions throughCheckPermission = Permissions.of(Permission.MANAGE_MESSAGES);

    private static final int suspectThreshold = 30;
    private static final int dangerousThreshold = 60;
    private static final int maxPreviewPages = 5;

    private static GuildMessageChannel staffChannel;
    private static Category sanctionDiscussCategory;

    @Override
    public ActionEvent<?> setupEvent() {

        repeatEachMillis(() -> { // Release behaviour
            ArrayList<String> atRemove = new ArrayList<>();
            for (String memId : membersWithBehaviour) {
                Mem mem = MemberCache.watch(memId);
                if (mem.getBehavior() != null && mem.getBehavior().canBeForgiven()) {
                    mem.removeBehavior();
                    atRemove.add(memId);
                }
            }
            atRemove.forEach(membersWithBehaviour::remove);
        }, 3600000 /*1 hour*/);


        staffChannel = (GuildMessageChannel) ChannelCache.watch(Core.data.staff_channel.asString()).entity;
        sanctionDiscussCategory = (Category) ChannelCache.watch(Core.data.sanction_discuss_category.asString()).entity;

        return (ActionEvent<MessageCreateEventFiller>) filler -> {

            if (filler.event.getMember().isEmpty()) { // Private channel.
                return;
            }

            Mem mem = MemberCache.get(filler.event.getMember().get().getId().asString());

            if (mem.entity.isBot()) // avoid flag bots
                return;

            if (throughCheckPermission.isMemberOwningPermissions(mem.entity)) { // TODO May improve permission system.
                return;
            }

            GuildMessageChannel currentChannel = (GuildMessageChannel) ChannelCache.watch(filler.event.getMessage().getChannelId().asString()).entity;

            if (!currentChannel.getData().parentId().isAbsent()
                && currentChannel.getData().parentId().get().isPresent()
                && currentChannel.getData().parentId().get().get().asString().equals(Core.data.sanction_discuss_category.asString())) {
                // If message is in a flagged channel do nothing
                return;
            }

            if (mem.getBehavior() != null && mem.getBehavior().flagged) {
                // Return already and delete message bc already flagged !
                filler.event.getMessage().delete().subscribe();
                return;
            }

            Message message = filler.event.getMessage();

            if (mem.getBehavior() == null) { // Cond could be removed. initBehavior do not erase old behavior.
                mem.initBehavior();
            }

            MemberBehavior behavior = mem.getBehavior();
            assert (behavior != null);

            membersWithBehaviour.add(mem.getSId());
            behavior.recordMessage(message);

            int behaviorScore = behavior.getBehaviorScore();

            if (behaviorScore > dangerousThreshold && behavior.flag()) { // flag() avoid multiple flag !
                startRiskMemberProcedure(mem);
            } else if (behaviorScore > suspectThreshold) {
                // Not implemented
            }
        };
    }

    @Override
    public void onStop() {

    }

    private static void startRiskMemberProcedure(Mem mem) {
        Logger.logMessage("Putted <@" + mem.entity.getId() + "> => " + mem.entity.getUsername() + " as risked member.");

        Member member = mem.entity;
        MemberBehavior behavior = mem.getBehavior();

        Optional<Instant> joinTime = member.getJoinTime();

        String joinTimeText;
        if (joinTime.isPresent()) {
            joinTimeText = String.format("<t:%d:d>", Math.round(joinTime.get().toEpochMilli() / 1000.0));
        } else {
            joinTimeText = "*inconnu*";
        }

        // The previews are built before deleting the messages, so nothing is lost.
        List<Message> lastMessages = behavior.getLastMessages();
        List<String> previews = new ArrayList<>();
        for (int i = 0; i < lastMessages.size(); i++) {
            previews.add(SpamReportFormatter.preview(i + 1, lastMessages.get(i)));
        }
        byte[] transcript = SpamReportFormatter.transcript(member, lastMessages).getBytes(StandardCharsets.UTF_8);
        String transcriptName = "anti-spam-" + member.getId().asString() + ".txt";

        // Keep the roles to give them back if the member is released.
        savedRoles.put(member.getId().asString(), new HashSet<>(member.getRoleIds()));

        // Add spammer role and remove other roles.
        member.edit(GuildMemberEditSpec.builder()
            .roles(Core.data.spam_detected_role)
            .build()).block();

        // delete all recent messages
        for (Message message : lastMessages) {
            message.delete("Possible spam détecté").subscribe();
        }

        Set<PermissionOverwrite> permissionsOfChannel = getPermissionOverwrites(member);

        // create sanction discuss channel
        TextChannel textChannel = Core.devarea.createTextChannel(TextChannelCreateSpec.builder() // TODO improve channel permissions.
            .name("⚠️｜Anti-Spam｜" + member.getDisplayName())
            .parentId(sanctionDiscussCategory.getId())
            .permissionOverwrites(permissionsOfChannel)
            .build()).block(Duration.ofSeconds(10));


        String memberIdString = member.getId().asString();
        String channelIdString = textChannel.getId().asString();

        String firstPreview = previews.isEmpty() ? "*(aucun message)*" : previews.get(0);

        staffChannel.createMessage(MessageCreateSpec.builder()
            .addEmbed(
                EmbedCreateSpec.builder()
                    .color(Color.RED).title("Une tentative de spam a été détectée !")
                    .author(member.getDisplayName(), null, member.getAvatarUrl())
                    .description(String.format("L'utilisateur %s (`%s`), membre du server depuis %s\n\nIl a été confiné dans le salon %s et ses derniers messages ont été supprimés.\nLe détail complet des messages est dans le fichier joint.", member.getMention(), member.getUsername(), joinTimeText, textChannel.getMention()))
                    .footer("En cas d'erreur, vous pouvez libérer l'utilisateur", null)
                    .addField(
                        String.format("Comportement sur les %d dernières secondes", Math.round(behavior.getRecordDuration() / 1000.0)),
                        String.format("- %d message.s envoyé.s\n- %d salon.s différent.s\n- %d lien.s d'invitation\n- %d autre.s lien.s\n- %d mention.s\n- %d message.s dupliqué.s\n- %d message.s transféré.s\n- %d pièce.s jointe.s\n- score de mots suspects: %d\n\n**> Score total: %d**",
                            behavior.messageStreak, behavior.channelsId.size(), behavior.invitesCount, behavior.linksCount,
                            behavior.mentionsCount, behavior.duplicatesCount, behavior.forwardedCount,
                            behavior.attachmentsCount, behavior.suspectWordsScore, behavior.getBehaviorScore()),
                        false)
                    .addField("Aperçu du premier message", SpamReportFormatter.truncate(firstPreview, SpamReportFormatter.MAX_EMBED_FIELD), false)
                    .build()
            )
            .addFile(MessageCreateFields.File.of(transcriptName, new ByteArrayInputStream(transcript)))
            .addComponent(
                ActionRow.of(
                    Button.danger("antiSpam_ban_" + memberIdString + "_" + channelIdString, Emoji.unicode("⛔"), "Bannir"),
                    Button.danger("antiSpam_muteWeek_" + memberIdString + "_" + channelIdString, Emoji.unicode("🔇"), "Mute 1 semaine"),
                    Button.primary("antiSpam_muteDay_" + memberIdString + "_" + channelIdString, Emoji.unicode("🔉"), "Mute 1 jour"),
                    Button.success("antiSpam_free_" + memberIdString + "_" + channelIdString, Emoji.unicode("✅"), "Libérer")
                )
            )
            .build()
        ).subscribe();

        List<MessageCreateSpec> sanctionMessages = new ArrayList<>();
        sanctionMessages.add(MessageCreateSpec.builder().addEmbed(EmbedCreateSpec.builder()
            .color(Color.RED).title("Discussion de sanction")
            .author(member.getDisplayName(), null, member.getAvatarUrl())
            .description(String.format("%s, tu as été détecté par l'**anti spam**.\n\nTu peux **discuter** de la sanction avec le staff, mais sache que les messages envoyés ont été **enregistrés**.", member.getMention()))
            .footer("En cas d'erreur, vous pouvez libérer l'utilisateur.", null)
            .build()).build());

        List<String> pages = SpamReportFormatter.paginate(previews, SpamReportFormatter.MAX_EMBED_DESCRIPTION);
        if (pages.isEmpty())
            pages.add("*(aucun message)*");
        int shownPages = Math.min(pages.size(), maxPreviewPages);

        for (int i = 0; i < shownPages; i++) {
            boolean last = i == shownPages - 1;
            String description = pages.get(i);
            if (last && pages.size() > shownPages)
                description = SpamReportFormatter.truncate(description, SpamReportFormatter.MAX_EMBED_DESCRIPTION - 100)
                    + "\n\n*… voir le fichier joint pour les autres messages.*";

            EmbedCreateSpec.Builder embed = EmbedCreateSpec.builder()
                .color(Color.ORANGE)
                .title(shownPages > 1 ? String.format("Aperçu des messages envoyés (%d/%d)", i + 1, shownPages) : "Aperçu des messages envoyés")
                .description(description);
            if (i == 0)
                embed.author(member.getDisplayName(), null, member.getAvatarUrl());
            if (last)
                embed.footer("Ces messages ont été envoyés par " + member.getUsername(), null);

            MessageCreateSpec.Builder spec = MessageCreateSpec.builder().addEmbed(embed.build());
            if (last)
                spec.addFile(MessageCreateFields.File.of(transcriptName, new ByteArrayInputStream(transcript)));
            sanctionMessages.add(spec.build());
        }

        // concatMap keeps the messages in order.
        Flux.fromIterable(sanctionMessages)
            .concatMap(spec -> textChannel.createMessage(spec)
                .onErrorResume(error -> {
                    Logger.logError("Anti-spam preview couldn't be sent : " + error.getMessage());
                    return Mono.empty();
                }))
            .subscribe();

        behavior.forgetBehavior();
    }

    /**
     * Gives back the roles a member had before being flagged by the anti-spam (or at least the rules accepted role).
     */
    public static Mono<Void> restoreRoles(final Member member) {
        Set<Snowflake> roles = savedRoles.remove(member.getId().asString());

        Mono<Void> fallback = member.addRole(Core.data.rulesAccepted_role)
            .then(member.removeRole(Core.data.spam_detected_role));

        if (roles == null) // Bot restarted since the flag.
            return fallback;

        Set<Snowflake> restored = new HashSet<>(roles);
        restored.remove(Core.data.spam_detected_role);
        restored.add(Core.data.rulesAccepted_role);

        return member.edit(GuildMemberEditSpec.builder().roles(restored).build())
            .then()
            .onErrorResume(error -> {
                // A saved role could have been deleted meanwhile.
                Logger.logError("Couldn't restore the roles of " + member.getId().asString() + " : " + error.getMessage());
                return fallback;
            });
    }

    public static void forgetSavedRoles(final String memberId) {
        savedRoles.remove(memberId);
    }

    private static Set<PermissionOverwrite> getPermissionOverwrites(Member member) {
        Set<PermissionOverwrite> set = new HashSet<>();
        set.add(PermissionOverwrite.forRole(Core.data.rulesAccepted_role, PermissionSet.of(), PermissionSet.of(Permission.VIEW_CHANNEL)));
        set.add(PermissionOverwrite.forRole(Core.devarea.getEveryoneRole().block().getId(), PermissionSet.of(), PermissionSet.of(Permission.VIEW_CHANNEL)));
        set.add(PermissionOverwrite.forMember(member.getId(), PermissionSet.of(Permission.VIEW_CHANNEL, Permission.READ_MESSAGE_HISTORY, Permission.SEND_MESSAGES), PermissionSet.of()));
        set.add(PermissionOverwrite.forRole(Core.data.admin_role, PermissionSet.of(Permission.VIEW_CHANNEL), PermissionSet.of()));
        set.add(PermissionOverwrite.forRole(Core.data.modo_role, PermissionSet.of(Permission.VIEW_CHANNEL), PermissionSet.of()));
        return set;
    }
}