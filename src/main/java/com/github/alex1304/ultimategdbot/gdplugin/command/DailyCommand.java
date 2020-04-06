package com.github.alex1304.ultimategdbot.gdplugin.command;

import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDoc;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDescriptor;
import com.github.alex1304.ultimategdbot.gdplugin.GDService;
import com.github.alex1304.ultimategdbot.gdplugin.util.GDLevels;

import reactor.core.publisher.Mono;

@CommandDescriptor(
		aliases = { "daily", "dailylevel" },
		shortDescription = "Displays info on the current Daily level."
)
public class DailyCommand {

	private final GDService gdService;
	
	public DailyCommand(GDService gdService) {
		this.gdService = gdService;
	}

	@CommandAction
	@CommandDoc("Displays level info as well as cooldown until the next Daily level.")
	public Mono<Void> run(Context ctx) {
		return GDLevels.sendTimelyInfo(ctx, gdService.getGdClient(), false).then();
	}
}
