/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.examine;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.client.chat.ChatColor;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ChatMessage;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.GameStateChanged;
import net.runelite.client.events.MenuOptionClicked;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.examine.ExamineClient;
import net.runelite.http.api.item.ItemPrice;

/**
 * Submits exammine info to the api
 *
 * @author Adam
 */
@PluginDescriptor(
	name = "Examine plugin"
)
@Slf4j
public class ExaminePlugin extends Plugin
{
	private static final float HIGH_ALCHEMY_CONSTANT = 0.6f;

	private final ExamineClient examineClient = new ExamineClient();
	private final Deque<PendingExamine> pending = new ArrayDeque<>();
	private final Cache<CacheKey, Boolean> cache = CacheBuilder.newBuilder()
		.maximumSize(128L)
		.build();

	@Inject
	Client client;

	@Inject
	ExamineConfig config;

	@Inject
	ItemManager itemManager;

	@Inject
	ChatMessageManager chatMessageManager;

	@Inject
	ScheduledExecutorService executor;

	@Provides
	ExamineConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExamineConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("examine"))
		{
			cacheConfiguredColors();
			chatMessageManager.refreshAll();
		}
	}

	private void cacheConfiguredColors()
	{
		chatMessageManager
			.cacheColor(new ChatColor(ChatColorType.NORMAL, config.getExamineRecolor(), false),
				ChatMessageType.EXAMINE_ITEM, ChatMessageType.EXAMINE_NPC, ChatMessageType.EXAMINE_OBJECT)
			.cacheColor(new ChatColor(ChatColorType.HIGHLIGHT, config.getExamineHRecolor(), false),
				ChatMessageType.EXAMINE_ITEM, ChatMessageType.EXAMINE_NPC, ChatMessageType.EXAMINE_OBJECT)
			.cacheColor(new ChatColor(ChatColorType.NORMAL, config.getTransparentExamineRecolor(), true),
				ChatMessageType.EXAMINE_ITEM, ChatMessageType.EXAMINE_NPC, ChatMessageType.EXAMINE_OBJECT)
			.cacheColor(new ChatColor(ChatColorType.HIGHLIGHT, config.getTransparentExamineHRecolor(), true),
				ChatMessageType.EXAMINE_ITEM, ChatMessageType.EXAMINE_NPC, ChatMessageType.EXAMINE_OBJECT);
	}

	@Subscribe
	public void onGameStateChange(GameStateChanged event)
	{
		pending.clear();

		if (event.getGameState().equals(GameState.LOGIN_SCREEN))
		{
			cacheConfiguredColors();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		ExamineType type;
		int id;
		switch (event.getMenuAction())
		{
			case EXAMINE_ITEM:
				type = ExamineType.ITEM;
				id = event.getId();
				break;
			case EXAMINE_OBJECT:
				type = ExamineType.OBJECT;
				id = event.getId() >>> 14;
				break;
			case EXAMINE_NPC:
				type = ExamineType.NPC;
				id = event.getId();
				break;
			default:
				return;
		}

		PendingExamine pendingExamine = new PendingExamine(type, id, Instant.now());
		pending.push(pendingExamine);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ExamineType type;
		switch (event.getType())
		{
			case EXAMINE_ITEM:
				type = ExamineType.ITEM;
				break;
			case EXAMINE_OBJECT:
				type = ExamineType.OBJECT;
				break;
			case EXAMINE_NPC:
				type = ExamineType.NPC;
				break;
			default:
				return;
		}

		if (pending.isEmpty())
		{
			log.debug("Got examine without a pending examine?");
			return;
		}

		PendingExamine pendingExamine = pending.pop();

		if (pendingExamine.getType() != type)
		{
			log.debug("Type mismatch for pending examine: {} != {}", pendingExamine.getType(), type);
			pending.clear(); // eh
			return;
		}

		log.debug("Got examine for {} {}: {}", pendingExamine.getType(), pendingExamine.getId(), event.getMessage());

		if (config.itemPrice() && pendingExamine.getType() == ExamineType.ITEM)
		{
			executor.submit(() -> getItemPrice(pendingExamine));
		}

		CacheKey key = new CacheKey(type, pendingExamine.getId());
		Boolean cached = cache.getIfPresent(key);
		if (cached != null)
		{
			return;
		}

		cache.put(key, Boolean.TRUE);
		executor.submit(() -> submitExamine(pendingExamine, event.getMessage()));
	}

	private void getItemPrice(PendingExamine examine)
	{
		try
		{
			final ItemComposition itemComposition = itemManager.getItemComposition(examine.getId());

			if (itemComposition != null)
			{
				final int id = itemComposition.getNote() != -1 ? itemComposition.getLinkedNoteId() : itemComposition.getId();
				final ItemPrice itemPrice = itemManager.getItemPrice(id);
				final int gePrice = itemPrice == null ? 0 : itemPrice.getPrice();
				final int alchPrice = Math.round(itemComposition.getPrice() * HIGH_ALCHEMY_CONSTANT);

				final String message = new ChatMessageBuilder()
					.append(ChatColorType.NORMAL)
					.append("Price of ")
					.append(ChatColorType.HIGHLIGHT)
					.append(itemComposition.getName())
					.append(ChatColorType.NORMAL)
					.append(": GE average ")
					.append(ChatColorType.HIGHLIGHT)
					.append(String.valueOf(gePrice))
					.append(ChatColorType.NORMAL)
					.append(" HA value ")
					.append(ChatColorType.HIGHLIGHT)
					.append(String.valueOf(alchPrice))
					.build();

				chatMessageManager.queue(ChatMessageType.EXAMINE_ITEM, message);
			}
		}
		catch (IOException e)
		{
			log.warn("Error looking up item price", e);
		}
	}

	private void submitExamine(PendingExamine examine, String text)
	{
		int id = examine.getId();

		try
		{
			switch (examine.getType())
			{
				case ITEM:
					examineClient.submitItem(id, text);
					break;
				case OBJECT:
					examineClient.submitObject(id, text);
					break;
				case NPC:
					examineClient.submitNpc(id, text);
					break;
			}
		}
		catch (IOException ex)
		{
			log.warn("Error submitting examine", ex);
		}
	}

}
