package devarea.fr.discord.workers.self;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;


import devarea.fr.discord.Core;
import devarea.fr.discord.cache.ChannelCache;
import devarea.fr.discord.cache.MemberCache;
import devarea.fr.discord.entities.Chan;
import devarea.fr.discord.commands.Permissions;
import devarea.fr.discord.entities.ActionEvent;
import devarea.fr.discord.entities.Mem;
import devarea.fr.discord.entities.events_filler.ButtonInteractionEventFiller;
import devarea.fr.discord.statics.ColorsUsed;
import devarea.fr.discord.statics.TextMessage;
import devarea.fr.discord.workers.Worker;
import devarea.fr.utils.Logger;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.spec.BanQuerySpec;
import discord4j.core.spec.EmbedCreateFields;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import discord4j.core.spec.MessageEditSpec;
import discord4j.discordjson.possible.Possible;
import discord4j.rest.util.Permission;

public class AntiSpamButtonsWorker implements Worker {
    private static final Permissions managePunishment = Permissions.of(Permission.MANAGE_MESSAGES);
    /**
     * Ban deletes the messages of the last 24 hours : the staff can take the decision long after the detection.
     */
    private static final int banDeleteMessageSeconds = 24 * 60 * 60;
    // Reports (sanction channel ids) already handled, avoid two staff members to take a decision at the same time.
    private static final Set<String> handledReports = ConcurrentHashMap.newKeySet();

    @Override
    public ActionEvent<?> setupEvent() {
        return (ActionEvent<ButtonInteractionEventFiller>) filler -> {

            String eventId = filler.event.getCustomId();

            if (!eventId.startsWith("antiSpam_")) {
                return;
            }

            String[] idData = eventId.split("_");
            if (idData.length < 4)
                return;

            String memberId = idData[2];
            String reportId = idData[3];
            Mem memStaff = filler.mem;

            if (!managePunishment.isMemberOwningPermissions(memStaff.entity)) {
                replyError(filler, "Vous n'avez pas les permissions pour donner la punition adéquate.");
                return;
            }

            // The member could have left the server after spamming : only the ban is still possible.
            Mem memRisk;
            try {
                memRisk = MemberCache.watch(memberId);
            } catch (Exception e) {
                memRisk = null;
            }
            if (memRisk == null && !idData[1].equals("ban")) {
                replyError(filler, "L'utilisateur a quitté le serveur, il ne peut plus qu'être banni.");
                return;
            }

            if (!handledReports.add(reportId)) {
                replyError(filler, "Une décision a déjà été prise pour ce signalement.");
                return;
            }

            GuildMessageChannel textChannel;
            try {
                Chan<?> chan = ChannelCache.watch(reportId);
                textChannel = chan == null ? null : (GuildMessageChannel) chan.entity;
            } catch (Exception e) {
                textChannel = null; // Sanction channel already deleted.
            }

            if (memRisk != null)
                memRisk.removeBehavior(); // The staff took a decision, the anti spam can watch the member again.


            switch (idData[1]) {
                case "ban":
                    Logger.logMessage("Banning " + memberId + " by auto spam validated by " + memStaff.getSId() + ".");

                    // Ban by id so it also works if the member already left the server.
                    Core.devarea.ban(Snowflake.of(memberId), BanQuerySpec.builder()
                        .reason("Auto Spam detected, validated by " + memStaff.entity.getUsername())
                        .deleteMessageSeconds(banDeleteMessageSeconds)
                        .build()).subscribe();
                    AntiSpamWorker.forgetSavedRoles(memberId);

                    replyAndEnd(filler, TextMessage.userHasBeenBanned, textChannel, memStaff);
                    break;

                case "muteWeek":
                    Logger.logMessage("Muting or a week " + memRisk.getSId() + " flagged by auto spam by " + memStaff.getSId() + ".");


                    memRisk.entity.edit().withCommunicationDisabledUntil(
                        Possible.of(Optional.of(Instant.now().plus(7, ChronoUnit.DAYS)))
                    ).subscribe();

                    AntiSpamWorker.restoreRoles(memRisk.entity).subscribe();

                    replyAndEnd(filler, TextMessage.userHasBeenMutedWeek, textChannel, memStaff);
                    break;

                case "muteDay":
                    Logger.logMessage("Muting or a day " + memRisk.getSId() + " flagged by auto spam by " + memStaff.getSId() + ".");

                    memRisk.entity.edit().withCommunicationDisabledUntil(
                        Possible.of(Optional.of(Instant.now().plus(1, ChronoUnit.DAYS)))
                    ).subscribe();

                    AntiSpamWorker.restoreRoles(memRisk.entity).subscribe();

                    replyAndEnd(filler, TextMessage.userHasBeenMutedDay, textChannel, memStaff);
                    break;

                case "free":
                    Logger.logMessage("Releasing " + memRisk.getSId() + " flagged by auto spam by " + memStaff.getSId() + ".");

                    AntiSpamWorker.restoreRoles(memRisk.entity).subscribe();

                    replyAndEnd(filler, TextMessage.userHasBeenReleased, textChannel, memStaff);
                    break;

                default:
                    handledReports.remove(reportId);
                    break;
            }
        };
    }

    @Override
    public void onStop() {

    }

    private static void replyError(final ButtonInteractionEventFiller filler, final String error) {
        filler.event.reply(InteractionApplicationCommandCallbackSpec.builder()
            .ephemeral(true)
            .addEmbed(EmbedCreateSpec.builder()
                .title("Error !")
                .description(error)
                .color(ColorsUsed.wrong)
                .build())
            .build()).subscribe();
    }

    private static void replyAndEnd(final ButtonInteractionEventFiller filler, final EmbedCreateSpec embed, final GuildMessageChannel textChannel, final Mem staffMember) {
        Optional<Message> message = filler.event.getMessage();

        filler.event.reply(InteractionApplicationCommandCallbackSpec.builder()
            .addEmbed(embed.withAuthor(EmbedCreateFields.Author.of(staffMember.entity.getUsername(), null, staffMember.entity.getAvatarUrl())))
            .build()).subscribe();


        if (message.isPresent()) {
            message.get().edit(MessageEditSpec.builder().componentsOrNull(new ArrayList<>()).build()).subscribe();
        }

        if (textChannel != null) {
            textChannel.delete().subscribe();
        }
    }
}
