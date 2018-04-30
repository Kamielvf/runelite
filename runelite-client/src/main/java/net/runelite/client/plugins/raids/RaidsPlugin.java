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

import com.google.common.eventbus.Subscribe;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InstanceTemplates;
import net.runelite.api.ObjectID;
import net.runelite.api.Point;
import net.runelite.api.VarPlayer;
import net.runelite.api.Tile;
import net.runelite.api.Varbits;
import static net.runelite.api.Perspective.SCENE_SIZE;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ConfigChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MapRegionChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetHiddenChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatColor;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.raids.solver.Layout;
import net.runelite.client.plugins.raids.solver.LayoutSolver;
import net.runelite.client.plugins.raids.solver.RotationSolver;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Chambers Of Xeric"
)
@Slf4j
public class RaidsPlugin extends Plugin
{
	private static final int LOBBY_PLANE = 3;
	private static final String RAID_START_MESSAGE = "The raid has begun!";
	private static final String LEVEL_COMPLETE_MESSAGE = "level complete!";
	private static final String RAID_COMPLETE_MESSAGE = "Congratulations - your raid is complete!";
	private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("###.##");
	public static final DecimalFormat POINTS_FORMAT = new DecimalFormat("#,###");
	private static final String SPLIT_REGEX = "\\s*,\\s*";
	private static final Pattern ROTATION_REGEX = Pattern.compile("\\[(.*?)\\]");

	private BufferedImage raidsIcon;
	private RaidsTimer timer;
	private boolean raidOngoing = false;

	@Getter
	private boolean inRaidChambers;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private Client client;

	@Inject
	private RaidsConfig config;

	@Inject
	private RaidsScoutOverlay overlay;

	@Inject
	private RaidsPointsOverlay pointsOverlay;

	@Inject
	private LayoutSolver layoutSolver;

	@Getter
	private Raid raid;

	@Getter
	private ArrayList<String> roomWhitelist = new ArrayList<>();

	@Getter
	private ArrayList<String> roomBlacklist = new ArrayList<>();

	@Getter
	private ArrayList<String> rotationWhitelist = new ArrayList<>();

	@Getter
	private ArrayList<String> layoutWhitelist = new ArrayList<>();

	@Provides
	RaidsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RaidsConfig.class);
	}

	@Override
	public List<Overlay> getOverlays()
	{
		return Arrays.asList(overlay, pointsOverlay);
	}

	@Override
	protected void startUp() throws Exception
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			inRaidChambers = client.getVar(Varbits.IN_RAID) == 1;
			updateInfoBoxState();
		}

		if (config.pointsMessage())
		{
			cacheColors();
		}

		updateLists();
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (timer != null)
		{
			infoBoxManager.removeInfoBox(timer);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (config.pointsMessage())
		{
			cacheColors();
		}

		if (event.getKey().equals("raidsTimer"))
		{
			updateInfoBoxState();
		}

		if (event.getKey().equals("whitelistedRooms"))
		{
			updateList(roomWhitelist, config.whitelistedRooms());
		}

		if (event.getKey().equals("blacklistedRooms"))
		{
			updateList(roomBlacklist, config.blacklistedRooms());
		}

		if (event.getKey().equals("whitelistedRotations"))
		{
			updateList(rotationWhitelist, config.whitelistedRotations());
		}

		if (event.getKey().equals("whitelistedLayouts"))
		{
			updateList(layoutWhitelist, config.whitelistedLayouts());
		}
	}

	@Subscribe
	public void onWidgetHiddenChanged(WidgetHiddenChanged event)
	{
		if (!inRaidChambers || event.isHidden())
		{
			return;
		}

		Widget widget = event.getWidget();

		if (widget == client.getWidget(WidgetInfo.RAIDS_POINTS_INFOBOX))
		{
			widget.setHidden(true);
		}
	}

	@Subscribe
	public void onVarbitChange(VarbitChanged event)
	{
		boolean setting = client.getVar(Varbits.IN_RAID) == 1;

		if (inRaidChambers != setting)
		{
			inRaidChambers = setting;
			updateInfoBoxState();

			if (inRaidChambers)
			{
				raid = buildRaid();

				if (raid == null)
				{
					log.debug("Failed to build raid");
					return;
				}

				Layout layout = layoutSolver.findLayout(raid.toCode());

				if (layout == null)
				{
					log.debug("Could not find layout match");
					return;
				}

				raid.updateLayout(layout);
				RotationSolver.solve(raid.getCombatRooms());
				overlay.setScoutOverlayShown(true);
			}
			else if (!config.scoutOverlayAtBank())
			{
				overlay.setScoutOverlayShown(false);
				raid = null;
			}
		}

		if (client.getVar(VarPlayer.IN_RAID_PARTY) == -1)
		{
			if (!inRaidChambers || !config.scoutOverlayDuringRaid())
			{
				overlay.setScoutOverlayShown(false);
				raid = null;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (inRaidChambers && event.getType() == ChatMessageType.CLANCHAT_INFO)
		{
			String message = Text.removeTags(event.getMessage());

			if (config.raidsTimer() && message.startsWith(RAID_START_MESSAGE))
			{
				raidOngoing = true;
				timer = new RaidsTimer(getRaidsIcon(), this, Instant.now());
				infoBoxManager.addInfoBox(timer);
			}

			if (timer != null && message.contains(LEVEL_COMPLETE_MESSAGE))
			{
				timer.timeFloor();
			}

			if (message.startsWith(RAID_COMPLETE_MESSAGE))
			{
				if (timer != null)
				{
					timer.timeFloor();
					timer.setStopped(true);
				}

				if (config.pointsMessage())
				{
					int totalPoints = client.getVar(Varbits.TOTAL_POINTS);
					int personalPoints = client.getVar(Varbits.PERSONAL_POINTS);

					double percentage = personalPoints / (totalPoints / 100.0);

					String chatMessage = new ChatMessageBuilder()
							.append(ChatColorType.NORMAL)
							.append("Total points: ")
							.append(ChatColorType.HIGHLIGHT)
							.append(POINTS_FORMAT.format(totalPoints))
							.append(ChatColorType.NORMAL)
							.append(", Personal points: ")
							.append(ChatColorType.HIGHLIGHT)
							.append(POINTS_FORMAT.format(personalPoints))
							.append(ChatColorType.NORMAL)
							.append(" (")
							.append(ChatColorType.HIGHLIGHT)
							.append(DECIMAL_FORMAT.format(percentage))
							.append(ChatColorType.NORMAL)
							.append("%)")
							.build();

					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.CLANCHAT_INFO)
						.runeLiteFormattedMessage(chatMessage)
						.build());
				}
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (!inRaidChambers || !raidOngoing || event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		checkUnknownRooms();
	}

	private void checkUnknownRooms()
	{
		Arrays.stream(raid.getRooms()).filter(x -> x.getType() == RaidRoom.Type.UNKNOWN ||
			(x.getType() == RaidRoom.Type.COMBAT && x.getBoss() == RaidRoom.Boss.UNKNOWN) ||
			(x.getType() == RaidRoom.Type.PUZZLE && x.getPuzzle() == RaidRoom.Puzzle.UNKNOWN)).forEach(x ->
		{
			WorldPoint worldPoint = x.getBase();
			int xCoord = worldPoint.getX() - client.getBaseX();

			//in case the room is out of scene it is skipped
			if (xCoord >= SCENE_SIZE || xCoord <= -RaidRoom.ROOM_MAX_SIZE)
			{
				return;
			}
			else if (xCoord <= 0)
			{
				//this would mean the room is partially visible, so we check the first tile of the scene
				xCoord = 1;
			}

			Tile tile = client.getRegion().getTiles()[worldPoint.getPlane()][xCoord][worldPoint.getY() - client.getBaseY()];
			RaidRoom room = determineRoom(tile, 0);
			x.setType(room.getType());
			x.setBoss(room.getBoss());
			x.setPuzzle(room.getPuzzle());
		});

		RotationSolver.solve(raid.getCombatRooms());
	}

	private void updateInfoBoxState()
	{
		if (timer != null)
		{
			if (inRaidChambers && config.raidsTimer())
			{
				infoBoxManager.addInfoBox(timer);
			}
			else
			{
				infoBoxManager.removeInfoBox(timer);
			}

			if (!inRaidChambers)
			{
				timer = null;
			}
		}
	}

	private void updateLists()
	{
		updateList(roomWhitelist, config.whitelistedRooms());
		updateList(roomBlacklist, config.blacklistedRooms());
		updateList(rotationWhitelist, config.whitelistedRotations());
		updateList(layoutWhitelist, config.whitelistedLayouts());
	}

	private void updateList(ArrayList<String> list, String input)
	{
		list.clear();

		if (list == rotationWhitelist)
		{
			Matcher m = ROTATION_REGEX.matcher(input);
			while (m.find())
			{
				String rotation = m.group(1).toLowerCase();

				if (!list.contains(rotation))
				{
					list.add(rotation);
				}
			}
		}
		else
		{
			list.addAll(Arrays.asList(input.toLowerCase().split(SPLIT_REGEX)));
		}
	}

	private void cacheColors()
	{
		chatMessageManager.cacheColor(new ChatColor(ChatColorType.NORMAL, Color.BLACK, false), ChatMessageType.CLANCHAT_INFO)
				.cacheColor(new ChatColor(ChatColorType.HIGHLIGHT, Color.RED, false), ChatMessageType.CLANCHAT_INFO)
				.cacheColor(new ChatColor(ChatColorType.NORMAL, Color.WHITE, true), ChatMessageType.CLANCHAT_INFO)
				.cacheColor(new ChatColor(ChatColorType.HIGHLIGHT, Color.RED, true), ChatMessageType.CLANCHAT_INFO)
				.refreshAll();
	}

	public int getRotationMatches()
	{
		String rotation = raid.getRotationString().toLowerCase();
		String[] bosses = rotation.split(SPLIT_REGEX);

		if (rotationWhitelist.contains(rotation))
		{
			return bosses.length;
		}

		for (String whitelisted : rotationWhitelist)
		{
			int matches = 0;
			String[] whitelistedBosses = whitelisted.split(SPLIT_REGEX);

			for (int i = 0; i < whitelistedBosses.length; i++)
			{
				if (i < bosses.length && whitelistedBosses[i].equals(bosses[i]))
				{
					matches++;
				}
				else
				{
					matches = 0;
					break;
				}
			}

			if (matches >= 2)
			{
				return matches;
			}
		}

		return 0;
	}

	/**
	 * Determines the bottom left-most point of the raid
	 *
	 * @return the bottom left-most corner of the raid in region coords
	 */
	private Point findRaidBase()
	{
		Tile[][] tiles = client.getRegion().getTiles()[LOBBY_PLANE];
		Point lobbyBase = null;
		Point firstNonNull = null;

		for (int x = 0; x < SCENE_SIZE; x++)
		{
			for (int y = 0; y < SCENE_SIZE; y++)
			{
				if (tiles[x][y] == null)
				{
					continue;
				}
				else if (firstNonNull == null)
				{
					firstNonNull = tiles[x][y].getRegionLocation();
				}

				if (tiles[x][y].getWallObject() == null)
				{
					continue;
				}

				if (tiles[x][y].getWallObject().getId() == ObjectID.NULL_12231)
				{
					lobbyBase = tiles[x][y].getRegionLocation();
					break;
				}
			}

			if (lobbyBase != null)
			{
				break;
			}
		}

		if (lobbyBase == null || firstNonNull == null)
		{
			return null;
		}


		//if the remainder of this is 0, it means our first non-null tile is a valid point on the grid and we don't need to look further
		if ((lobbyBase.getX() - firstNonNull.getX()) % RaidRoom.ROOM_MAX_SIZE == 0)
		{
			return firstNonNull;
		}

		int baseX = lobbyBase.getX();
		int baseY = firstNonNull.getY(); //we always want the bottom row, so the first non-null tile's Y coord is always correct

		//based on checking if there is another room east of the lobby, we can determine the west-most room of the raid
		if (tiles[baseX + RaidRoom.ROOM_MAX_SIZE][baseY] == null)
		{
			baseX -= RaidRoom.ROOM_MAX_SIZE * 3;
		}
		else
		{
			baseX -= RaidRoom.ROOM_MAX_SIZE * 2;
		}

		return new Point(baseX, baseY);
	}

	private Raid buildRaid()
	{
		//find the raid base region coords as starting point for the grid
		Point raidBase = findRaidBase();

		if (raidBase == null)
		{
			return null;
		}

		Raid raid = new Raid();
		Tile[][] tiles;
		int roomBaseX, roomBaseY, roomTileOffsetX;
		int position = 0;

		for (int plane = 3; plane > 1; plane--)
		{
			tiles = client.getRegion().getTiles()[plane];

			for (int i = 1; i > -1; i--)
			{
				roomBaseY = raidBase.getY() + (i * RaidRoom.ROOM_MAX_SIZE);

				for (int j = 0; j < 4; j++)
				{
					RaidRoom room;
					roomBaseX = raidBase.getX() + (j * RaidRoom.ROOM_MAX_SIZE);

					if (roomBaseX < 0)
					{
						roomTileOffsetX = Math.abs(roomBaseX) + 1; //add 1 because the tile at x=0 will always be null
					}
					else
					{
						roomTileOffsetX = 0;
					}

					if (roomBaseX >= SCENE_SIZE || roomTileOffsetX >= RaidRoom.ROOM_MAX_SIZE)
					{
						//in this case the room is so far that it is not visible in any way on the map
						WorldPoint base = WorldPoint.fromRegion(client, roomBaseX, roomBaseY, plane);
						room = new RaidRoom(base, RaidRoom.Type.UNKNOWN);
					}
					else
					{
						Tile base = tiles[roomBaseX + roomTileOffsetX][roomBaseY];
						room = determineRoom(base, roomTileOffsetX);
					}

					raid.setRoom(room, position);
					position++;
				}
			}
		}

		return raid;
	}

	private RaidRoom determineRoom(Tile base, int tileOffset)
	{
		RaidRoom room = new RaidRoom(base.getWorldLocation().dx(-tileOffset), RaidRoom.Type.EMPTY);
		int chunkData = client.getInstanceTemplateChunks()[base.getPlane()][(base.getRegionLocation().getX()) / 8][base.getRegionLocation().getY() / 8];
		InstanceTemplates template = InstanceTemplates.findMatch(chunkData);

		if (template == null)
		{
			return room;
		}

		switch (template)
		{
			case RAIDS_LOBBY:
			case RAIDS_START:
				room.setType(RaidRoom.Type.START);
				break;

			case RAIDS_END:
				room.setType(RaidRoom.Type.END);
				break;

			case RAIDS_SCAVENGERS:
			case RAIDS_SCAVENGERS2:
				room.setType(RaidRoom.Type.SCAVENGERS);
				break;

			case RAIDS_SHAMANS:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.SHAMANS);
				break;

			case RAIDS_VASA:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.VASA);
				break;

			case RAIDS_VANGUARDS:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.VANGUARDS);
				break;

			case RAIDS_ICE_DEMON:
				room.setType(RaidRoom.Type.PUZZLE);
				room.setPuzzle(RaidRoom.Puzzle.ICE_DEMON);
				break;

			case RAIDS_THIEVING:
				room.setType(RaidRoom.Type.PUZZLE);
				room.setPuzzle(RaidRoom.Puzzle.THIEVING);
				break;

			case RAIDS_FARMING:
			case RAIDS_FARMING2:
				room.setType(RaidRoom.Type.FARMING);
				break;

			case RAIDS_MUTTADILES:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.MUTTADILES);
				break;

			case RAIDS_MYSTICS:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.MYSTICS);
				break;

			case RAIDS_TEKTON:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.TEKTON);
				break;

			case RAIDS_TIGHTROPE:
				room.setType(RaidRoom.Type.PUZZLE);
				room.setPuzzle(RaidRoom.Puzzle.TIGHTROPE);
				break;

			case RAIDS_GUARDIANS:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.GUARDIANS);
				break;

			case RAIDS_CRABS:
				room.setType(RaidRoom.Type.PUZZLE);
				room.setPuzzle(RaidRoom.Puzzle.CRABS);
				break;

			case RAIDS_VESPULA:
				room.setType(RaidRoom.Type.COMBAT);
				room.setBoss(RaidRoom.Boss.VESPULA);
				break;
		}

		return room;
	}

	private BufferedImage getRaidsIcon()
	{
		if (raidsIcon != null)
		{
			return raidsIcon;
		}
		try
		{
			synchronized (ImageIO.class)
			{
				raidsIcon = ImageIO.read(RaidsPlugin.class.getResourceAsStream("raids_icon.png"));
			}
		}
		catch (IOException ex)
		{
			log.warn("Unable to load image", ex);
		}

		return raidsIcon;
	}
}
