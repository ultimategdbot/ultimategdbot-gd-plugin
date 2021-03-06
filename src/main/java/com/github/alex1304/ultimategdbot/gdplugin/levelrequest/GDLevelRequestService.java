package com.github.alex1304.ultimategdbot.gdplugin.levelrequest;

import static discord4j.core.retriever.EntityRetrievalStrategy.STORE_FALLBACK_REST;
import static java.util.Collections.synchronizedSet;
import static reactor.function.TupleUtils.function;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jdbi.v3.core.mapper.immutables.JdbiImmutables;

import com.github.alex1304.jdash.entity.GDLevel;
import com.github.alex1304.ultimategdbot.api.Translator;
import com.github.alex1304.ultimategdbot.api.command.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.service.BotService;
import com.github.alex1304.ultimategdbot.api.util.MessageSpecTemplate;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLevelRequestConfigDao;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLevelRequestConfigData;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLevelRequestReviewData;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLevelRequestSubmissionDao;
import com.github.alex1304.ultimategdbot.gdplugin.database.GDLevelRequestSubmissionData;
import com.github.alex1304.ultimategdbot.gdplugin.level.GDLevelService;

import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.MessageDeleteEvent;
import discord4j.core.object.Embed;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.retry.Retry;

public final class GDLevelRequestService {
	
	private static final Logger LOGGER = Loggers.getLogger(GDLevelRequestService.class);
	
	private final BotService bot;
	private final GDLevelService gdLevelService;
	
	private final Set<Long> cachedSubmissionChannelIds = synchronizedSet(new HashSet<Long>());
	
	public GDLevelRequestService(
			BotService bot,
			GDLevelService gdLevelService) {
		this.bot = bot;
		bot.database().configureJdbi(jdbi -> {
			jdbi.getConfig(JdbiImmutables.class).registerImmutable(
					GDLevelRequestConfigData.class,
					GDLevelRequestReviewData.class,
					GDLevelRequestSubmissionData.class);
		});
		bot.database().addGuildConfigurator(GDLevelRequestConfigDao.class,
				(data, tr) -> GDLevelRequestConfigData.configurator(data, tr, bot.gateway(), this));
		this.gdLevelService = gdLevelService;
		listenAndCleanSubmissionQueueChannels();
	}

	public final Set<Long> getCachedSubmissionChannelIds() {
		return cachedSubmissionChannelIds;
	}

	/**
	 * Retrieves the level requests config for the guild of the specified context.
	 * This method checks and throws errors if level requests are not configured.
	 * 
	 * @param ctx the context
	 * @return the level requests config, or an error if not configured
	 */
	public Mono<GDLevelRequestConfigData> retrieveConfig(Context ctx) {
		Objects.requireNonNull(ctx, "ctx was null");
		var guildId = ctx.event().getGuildId().orElseThrow();
		return bot.database()
				.withExtension(GDLevelRequestConfigDao.class, dao -> dao.getOrCreate(guildId.asLong()))
				.flatMap(lvlReqCfg -> !lvlReqCfg.isOpen()
						&& (lvlReqCfg.channelSubmissionQueueId().isEmpty()
						|| lvlReqCfg.channelArchivedSubmissionsId().isEmpty()
						|| lvlReqCfg.roleReviewerId().isEmpty())
						? Mono.error(new CommandFailedException(ctx.translate("GDStrings", "error_reqs_not_configured")))
						: Mono.just(lvlReqCfg));
	}
	
	/**
	 * Retrieves all submissions for the user in the guild of the specified context.
	 * 
	 * @param guildId the ID of the guild
	 * @return a Flux emitting all submissions before completing.
	 */
	public Flux<GDLevelRequestSubmissionData> retrieveSubmissionsForGuild(long guildId) {
		return bot.database()
				.withExtension(GDLevelRequestSubmissionDao.class, dao -> dao.getQueuedSubmissionsInGuild(guildId))
				.flatMapMany(Flux::fromIterable);
	}

	/**
	 * Builds the submission message from the given data.
	 * 
	 * @param tr             the translator to translate the strings
	 * @param author         the submission author
	 * @param level          the level
	 * @param lvlReqSettings the settings for level requests in the current server
	 * @param submission     the submission
	 * @param reviews        the list of reviews
	 * @return a Mono emitting the submission message
	 */
	public Mono<MessageSpecTemplate> buildSubmissionMessage(Translator tr, User author, GDLevel level,
			GDLevelRequestConfigData lvlReqSettings, GDLevelRequestSubmissionData submission, List<GDLevelRequestReviewData> reviews) {
		Objects.requireNonNull(tr, "tr was null");
		Objects.requireNonNull(author, "author was null");
		Objects.requireNonNull(level, "level was null");
		Objects.requireNonNull(lvlReqSettings, "lvlReqSettings was null");
		Objects.requireNonNull(submission, "submission was null");
		Objects.requireNonNull(reviews, "reviews was null");
		final var formatUser = author.getTag() + " (`" + author.getId().asLong() + "`)";
		return Mono.zip(gdLevelService.compactView(tr, level, tr.translate("GDStrings", "submission_title"), "https://i.imgur.com/yC9P4sT.png"),
				Flux.fromIterable(reviews)
						.map(GDLevelRequestReviewData::reviewerId)
						.flatMap(id -> bot.gateway()
								.withRetrievalStrategy(STORE_FALLBACK_REST)
								.getUserById(id)
								.onErrorResume(e -> Mono.empty()))
						.collectList())
				.map(function((embedSpecConsumer, reviewers) -> {
					var content = "**" + tr.translate("GDStrings", "label_submission_id") + "** `" + submission.submissionId() + "`\n"
							+ "**" + tr.translate("GDStrings", "label_submission_author") + "** " + formatUser + "\n"
							+ "**" + tr.translate("GDStrings", "label_submission_level_id") + "** `" + level.getId() + "`\n"
							+ "**" + tr.translate("GDStrings", "label_submission_yt") + "** "
							+ submission.youtubeLink().orElse('*' + tr.translate("GDStrings", "not_provided") + '*');
					var embed = embedSpecConsumer.andThen(embedSpec -> {
						embedSpec.addField("───────────", "**" + tr.translate("GDStrings", "label_reviews")
								+ "** " + reviews.size() + "/" + lvlReqSettings.minReviewsRequired(), false);
						for (var review : reviews) {
							var reviewerName = reviewers.stream()
									.filter(r -> r.getId().equals(review.reviewerId()))
									.map(User::getTag)
									.findAny()
									.orElse("Unknown User\\#0000")
									+ " (`" + review.reviewerId().asString() + "`)";
							embedSpec.addField(":pencil: " + reviewerName, review.reviewContent(), false);
						}
					});
					return new MessageSpecTemplate(content, embed);
				}));
	}
	
	/**
	 * Removes from database the submissions which associated Discord message is
	 * deleted. If the Discord message is inaccessible (e.g the bot can't see the
	 * submission channel or the message history anymore), the submission is
	 * untouched.
	 * 
	 * @param bot the bot
	 * @return a Mono completing when the process is done.
	 */
	public Mono<Void> cleanOrphanSubmissions() {
		return bot.database()
				.withExtension(GDLevelRequestSubmissionDao.class, GDLevelRequestSubmissionDao::getAllQueuedSubmissions)
				.flatMapMany(Flux::fromIterable)
				.filterWhen(submission -> bot.gateway().rest()
						.getMessageById(submission.messageChannelId().orElseThrow(), submission.messageId().orElseThrow())
						.getData()
						.hasElement()
						.onErrorReturn(true)
						.map(b -> !b))
				.map(GDLevelRequestSubmissionData::submissionId)
				.collectList()
				.flatMap(submissionsToDelete -> submissionsToDelete.isEmpty() ? Mono.just(0) : bot.database()
						.withExtension(GDLevelRequestSubmissionDao.class, dao -> dao.deleteAllIn(submissionsToDelete)))
				.doOnNext(count -> LOGGER.debug("Cleaned from database {} orphan level request submissions", count))
				.flatMap(count -> bot.emoji().get("info")
						.flatMap(info -> bot.logging().log(info + ' ' + bot.localization()
								.translate("GDStrings", "orphan_submissions_log", count))))
				.then();
	}
	
	/**
	 * Keep submission queue channels for level requests clean from messages that
	 * aren't submissions.
	 */
	private void listenAndCleanSubmissionQueueChannels() {
		bot.database().withExtension(GDLevelRequestConfigDao.class, GDLevelRequestConfigDao::getAll)
				.flatMapMany(Flux::fromIterable)
				.map(GDLevelRequestConfigData::channelSubmissionQueueId)
				.flatMap(Mono::justOrEmpty)
				.map(Snowflake::asLong)
				.collect(() -> cachedSubmissionChannelIds, Set::add)
				.thenMany(bot.gateway().on(MessageCreateEvent.class))
				.filter(event -> cachedSubmissionChannelIds.contains(event.getMessage().getChannelId().asLong()))
				.filter(event -> !event.getMessage().getAuthor().map(User::getId).map(event.getClient().getSelfId()::equals).orElse(false)
						|| !event.getMessage().getEmbeds().stream().findFirst()
								.flatMap(Embed::getAuthor)
								.map(a -> a.getIconUrl().equals("https://i.imgur.com/yC9P4sT.png"))
								.orElse(false))
				.flatMap(event -> Mono.just(event)
						.delayElement(Duration.ofSeconds(15))
						.flatMap(__ -> event.getMessage().delete().onErrorResume(e -> Mono.empty())))
				.retryWhen(Retry.indefinitely()
						.doBeforeRetry(retry -> LOGGER.error("Error while cleaning level requests submission queue channels", retry.failure())))
				.subscribe();
		
		bot.gateway().on(MessageDeleteEvent.class)
				.filter(event -> cachedSubmissionChannelIds.contains(event.getChannelId().asLong()))
				.map(event -> event.getMessage().map(Message::getId).map(Snowflake::asLong).orElse(0L))
				.filter(id -> id > 0)
				.flatMap(id -> bot.database().useExtension(GDLevelRequestSubmissionDao.class, dao -> dao.deleteByMessageId(id)))
				.retryWhen(Retry.indefinitely()
						.doBeforeRetry(retry -> LOGGER.error("Error while processing MessageDeleteEvent in submission queue channels", retry.failure())))
				.subscribe();
		
		Flux.interval(Duration.ofHours(12), Duration.ofDays(1))
				.flatMap(tick -> cleanOrphanSubmissions()
						.onErrorResume(e -> Mono.fromRunnable(() -> LOGGER.warn("Error while cleaning orphan level request submissions", e))))
				.subscribe();
	}
}
