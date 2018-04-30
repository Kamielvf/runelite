/*
 * Copyright (c) 2018, Kamiel
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
package net.runelite.client.plugins.raids;

import com.google.common.collect.ImmutableMap;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.BackgroundComponent;
import net.runelite.client.ui.overlay.components.TextComponent;

public class RaidsVanguardsOverlay extends Overlay
{
	//in ground = NpcID.VANGUARD
	//traveling trough ground = NpcID.VANGUARD_7526;
	private static final Map<Integer, Color> VANGUARDS = new ImmutableMap.Builder<Integer, Color>()
		.put(NpcID.VANGUARD_7527, Color.RED.darker()) //melee
		.put(NpcID.VANGUARD_7528, Color.GREEN.darker()) //ranged
		.put(NpcID.VANGUARD_7529, Color.BLUE.brighter()) //mage
		.build();

	private final int PADDING = 5;
	private final int GAP = 3;
	private final int OVERLAY_WIDTH = 129;
	private final int BAR_HEIGHT = 16;
	private final int OVERLAY_HEIGHT = BAR_HEIGHT * VANGUARDS.size() + GAP * (VANGUARDS.size() - 1) + PADDING * 2;
	private final int BAR_WIDTH = OVERLAY_WIDTH - PADDING * 2;
	private final Color BAR_BORDER_COLOR = Color.BLACK;
	private final Color BAR_BACKGROUND_COLOR = new Color(56, 48, 35);
	private final Font FONT = FontManager.getRunescapeSmallFont();
	private final DecimalFormat format = new DecimalFormat("#.#");

	private Map<Integer, Double> lastKnownHealth = new HashMap<>();
	private static final double DEFAULT_HEALTH_RATIO = 1D;

	@Inject
	private Client client;

	@Inject
	private RaidsPlugin plugin;

	@Inject
	private RaidsConfig config;

	@Inject
	RaidsVanguardsOverlay()
	{
		setPriority(OverlayPriority.MED);
		setPosition(OverlayPosition.TOP_RIGHT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isRaidOngoing() || !config.vanguardsOverlay())
		{
			return null;
		}

		RaidRoom.Boss currentBoss = plugin.getCurrentRoom().getBoss();

		if (currentBoss == null || currentBoss != RaidRoom.Boss.VANGUARDS)
		{
			return null;
		}

		final BackgroundComponent backgroundComponent = new BackgroundComponent();
		backgroundComponent.setRectangle(new Rectangle(0, 0, OVERLAY_WIDTH, OVERLAY_HEIGHT));
		backgroundComponent.render(graphics);

		graphics.setFont(FONT);

		int x = PADDING;
		int y = PADDING;

		for (Map.Entry<Integer, Color> entry : VANGUARDS.entrySet())
		{
			double healthRatio = DEFAULT_HEALTH_RATIO;
			int vanguardId = entry.getKey();

			if (lastKnownHealth.containsKey(vanguardId))
			{
				healthRatio = lastKnownHealth.get(vanguardId);
			}

			Color color = entry.getValue();
			drawHealthBar(graphics, x, y, color, healthRatio);
			y += BAR_HEIGHT + GAP;
		}

		return new Dimension(OVERLAY_WIDTH, OVERLAY_HEIGHT);
	}

	private void drawHealthBar(Graphics2D graphics, int x, int y, Color color, double healthRatio)
	{
		int barWidth = (int) (healthRatio * (BAR_WIDTH - 4));

		graphics.setColor(color);
		graphics.drawRect(x, y, BAR_WIDTH - 1, BAR_HEIGHT - 1);
		graphics.fillRect(x + 2, y + 2, barWidth, BAR_HEIGHT - 4);

		graphics.setColor(BAR_BORDER_COLOR);
		graphics.drawRect(x + 1, y + 1, BAR_WIDTH - 3, BAR_HEIGHT - 3);

		graphics.setColor(BAR_BACKGROUND_COLOR);
		graphics.fillRect(x + 2 + barWidth, y + 2, BAR_WIDTH - 4 - barWidth, BAR_HEIGHT - 4);

		String health = String.valueOf(format.format(healthRatio * 100.0)) + "%";
		int textWidth = graphics.getFontMetrics().stringWidth(health);
		int textLocationX = x + BAR_WIDTH / 2 - textWidth / 2;
		int textLocationY = y + BAR_HEIGHT / 2 + graphics.getFontMetrics().getHeight() / 2;

		TextComponent textComponent = new TextComponent();
		textComponent.setPosition(new Point(textLocationX, textLocationY));
		textComponent.setText(health);
		textComponent.render(graphics);
	}

	public void updateVanguardsHealth()
	{
		for (NPC npc : client.getNpcs())
		{
			int id = npc.getComposition().getId();

			if (VANGUARDS.containsKey(id))
			{
				int health = npc.getHealth();

				if (health < 0)
				{
					continue;
				}

				double healthRatio = (double) npc.getHealthRatio() / (double) health;
				lastKnownHealth.put(id, healthRatio);
			}
		}
	}

	public void reset()
	{
		lastKnownHealth.clear();
	}
}
