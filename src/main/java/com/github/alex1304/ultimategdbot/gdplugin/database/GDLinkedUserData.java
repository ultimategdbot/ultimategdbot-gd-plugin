package com.github.alex1304.ultimategdbot.gdplugin.database;

import java.util.Optional;

import org.immutables.value.Value;

import discord4j.common.util.Snowflake;

@Value.Immutable
public interface GDLinkedUserData {
	
	Snowflake discordUserId();
	
	long gdUserId();
	
	boolean isLinkActivated();
	
	Optional<String> confirmationToken();
}
