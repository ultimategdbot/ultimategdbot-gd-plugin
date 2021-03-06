package com.github.alex1304.ultimategdbot.gdplugin.command;

import com.github.alex1304.jdash.util.LevelSearchFilters;
import com.github.alex1304.ultimategdbot.api.command.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDescriptor;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDoc;
import com.github.alex1304.ultimategdbot.api.service.Root;
import com.github.alex1304.ultimategdbot.gdplugin.GDService;

import reactor.core.publisher.Mono;

@CommandDescriptor(
		aliases = "level",
		shortDescription = "tr:GDStrings/level_desc"
)
public final class LevelCommand {

	@Root
	private GDService gd;
	
	@CommandAction
	@CommandDoc("tr:GDStrings/level_run")
	public Mono<Void> run(Context ctx, String query) {
		if (!query.matches("[a-zA-Z0-9 _-]+")) {
			return Mono.error(new CommandFailedException(ctx.translate("GDStrings", "error_invalid_characters")));
		}
		return gd.level().searchAndSend(ctx, ctx.translate("GDStrings", "search_results", query),
				() -> gd.client().searchLevels(query, LevelSearchFilters.create(), 0));
	}
}
