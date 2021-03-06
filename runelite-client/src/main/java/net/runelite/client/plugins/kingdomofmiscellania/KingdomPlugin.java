/*
 * Copyright (c) 2018, Infinitay <https://github.com/Infinitay>
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
package net.runelite.client.plugins.kingdomofmiscellania;

import com.google.common.eventbus.Subscribe;
import com.google.common.primitives.Ints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Varbits;
import net.runelite.api.events.MapRegionChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

@PluginDescriptor(
	name = "Kingdom of Miscellania",
	enabledByDefault = false
)
@Slf4j
public class KingdomPlugin extends Plugin
{
	private static final int[] KINGDOM_REGION = {9787, 9788, 9789, 10043, 10044, 10045, 10299, 10300, 10301};

	@Inject
	private Client client;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Getter
	private int favor = 0, coffer = 0;

	private KingdomCounter counter;
	private BufferedImage counterImage;

	@Override
	protected void startUp() throws Exception
	{
		synchronized (ImageIO.class)
		{
			counterImage = ImageIO.read(getClass().getResourceAsStream("teak_chest.png"));
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		removeKingdomInfobox();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		favor = client.getSetting(Varbits.KINGDOM_FAVOR);
		coffer = client.getSetting(Varbits.KINGDOM_COFFER);
		processInfobox();
	}

	@Subscribe
	public void onRegionChanged(MapRegionChanged event)
	{
		processInfobox();
	}

	private void processInfobox()
	{
		if (client.getGameState() == GameState.LOGGED_IN && hasCompletedQuest() && isInKingdom())
		{
			addKingdomInfobox();
		}
		else
		{
			removeKingdomInfobox();
		}

	}

	private void addKingdomInfobox()
	{
		if (counter == null)
		{
			counter = new KingdomCounter(counterImage, this);
			infoBoxManager.addInfoBox(counter);
			log.debug("Added Kingdom Infobox");
		}
	}

	private void removeKingdomInfobox()
	{
		if (counter != null)
		{
			infoBoxManager.removeInfoBox(counter);
			counter = null;
			log.debug("Removed Kingdom Infobox");
		}
	}

	private boolean isInKingdom()
	{
		return Ints.indexOf(client.getMapRegions(), KINGDOM_REGION) >= 0;
	}

	private boolean hasCompletedQuest()
	{
		return client.getSetting(Varbits.THRONE_OF_MISCELLANIA_QUEST) == 1;
	}

	static int getFavorPercent(int favor)
	{
		return (favor * 100) / 127;
	}

}
