package com.github.alex1304.ultimategdbot.gdplugin.command;

import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.PermissionLevel;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandAction;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandDescriptor;
import com.github.alex1304.ultimategdbot.api.command.annotated.CommandPermission;
import com.github.alex1304.ultimategdbot.gdplugin.GDService;

import reactor.core.publisher.Mono;

@CommandDescriptor(
		aliases = "cleargdcache",
		shortDescription = "tr:strings_gd/cleargdcache_desc"
)
@CommandPermission(level = PermissionLevel.BOT_ADMIN)
public class ClearGdCacheCommand {
	
	private final GDService gdService;
	
	public ClearGdCacheCommand(GDService gdService) {
		this.gdService = gdService;
	}

	@CommandAction
	public Mono<Void> run(Context ctx) {
		return Mono.fromRunnable(gdService.getGdClient()::clearCache)
				.then(ctx.reply(ctx.translate("strings_gd", "cache_clear_success")))
				.then();
	}
}
