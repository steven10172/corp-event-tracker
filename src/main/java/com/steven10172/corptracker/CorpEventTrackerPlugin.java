package com.steven10172.corptracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Corp Event Tracker"
)
public class CorpEventTrackerPlugin extends Plugin {
	private final Pattern DROP_MESSAGE = Pattern.compile("<col=005f00>(.*) received a drop: (.+)</col>");
	private final Pattern VALUABLE_DROP_MESSAGE = Pattern.compile("<col=ef1020>Valuable drop: (.*) \\((.*)\\)</col>");
	private final Pattern DROP_QTY = Pattern.compile("(.+) x (.+)");
	private final Set<String> participants = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private final int BOSS_DELETE_IN_PROG_KILL_MS = 28 * 1000; // Corp respawn is 30 seconds

	private UUID killId;
	private NPC corp;
	private CorpEventTrackerPanel panel;
	private NavigationButton navButton;
	private Thread bosskillExpiredThread;
	private GameState lastGameState;
	private Timer checkForParticipantsTimer = null;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private CorpEventTrackerConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Override
	protected void startUp() throws Exception {
		log.info("Corp Event Tracker started!");

		panel = new CorpEventTrackerPanel(this, config, executor);
		renderBossIcon(ItemID.PET_CORPOREAL_CRITTER, 48, 48);


		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "/CorporealBeast.png");

		navButton = NavigationButton.builder()
				.tooltip("Corp Event")
				.icon(icon)
				.priority(6)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

//		preFillKills(); // Pre-fill kills for testing
	}

	@Override
	protected void shutDown() throws Exception {
		log.info("Corp Event Tracker stopped!");
		this.corp = null;
		this.killId = null;
		this.participants.clear();
		this.clearBossExpireThread();
		if (this.checkForParticipantsTimer != null) {
			this.checkForParticipantsTimer.cancel();
			this.checkForParticipantsTimer = null;
		}
		clientToolbar.removeNavigation(navButton);
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned) {
		NPC npc = npcSpawned.getNpc();

		if (npc.getId() == NpcID.CORPOREAL_BEAST) {
			log.info("Corporeal beast spawn: {}", npc);
			this.corp = npc;

			// If the last kill never expired re-use it
			this.clearBossExpireThread();

			// This is a new kill
			this.createAndLogNewKillIfNotPresent();

			// Cancel existing timer
			if (this.checkForParticipantsTimer != null) {
				this.checkForParticipantsTimer.cancel();
			}

			this.checkForParticipantsTimer = new Timer();
			checkForParticipantsTimer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					log.info("Checking for boss participants");
					client.getPlayers().forEach(player -> {
						if (corp != null && player.getInteracting() != null && player.getInteracting().getName().equals(corp.getName())) {
							log.info("Found participant: " + player.getName());
							participants.add(player.getName());
						}
					});
					createAndLogNewKillIfNotPresent();
				}
			},0,2500);
		}
	}

	private void createAndLogNewKillIfNotPresent() {
		if (this.killId == null) {
			log.info("Generating a new UUID");
			this.killId = UUID.randomUUID();
			this.participants.clear(); // Clear existing as kill wasn't active
		}

		this.logInProgressKill();
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned npcDespawned) {
		NPC npc = npcDespawned.getNpc();

		if (npc == this.corp) {
			log.info("Corporeal beast despawn: {}", npc);

			this.checkForParticipantsTimer.cancel();
			this.corp = null;

			if (npc.isDead()) {
				log.info("NPC Dead");
				// Clear the kill instantly, but prevent the possibility of the
				// kill message not being first
				this.clearNpcActiveKill(1000); // Clear the kill "instantly"
			} else {
				this.clearNpcActiveKill();
			}
		}
	}

	private void clearNpcActiveKill() {
		this.clearNpcActiveKill(BOSS_DELETE_IN_PROG_KILL_MS);
	}

	private void clearNpcActiveKill(int waitMs) {
		// Kill the current kill if nothing happened in the last 25 seconds
		this.clearBossExpireThread();

		if (this.killId == null) {
			// Make sure all participants are removed
			this.participants.clear();
		} else {
			log.info("Setting up boss expire thread");
			// Kill in progress still. Set a timer to clean up this kill after X seconds
			this.bosskillExpiredThread = setTimeout(() -> {
				log.info("Clearing participants");
				this.participants.clear();
				UUID curKillId = this.killId; // Save for later
				this.killId = null;

				SwingUtilities.invokeLater(() -> this.panel.removeKill(curKillId));
			}, waitMs);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage) {
		if (chatMessage.getType() == ChatMessageType.GAMEMESSAGE) {
			// Example: <col=005f00>Prized received a drop: 175 x Onyx bolts (e)</col>
			// Example: <col=ef1020>Valuable drop: 250 x Runite bolts (95,250 coins)</col>
			Matcher matcherValuable = VALUABLE_DROP_MESSAGE.matcher(chatMessage.getMessage());

			if (matcherValuable.lookingAt()) {
				String player = client.getUsername();
				int qty = 1;
				String dropName = matcherValuable.group(1);

				Matcher matcher2 = DROP_QTY.matcher(dropName);
				if (matcher2.lookingAt()) {
					qty = Integer.parseInt(matcher2.group(1).replaceAll(",", ""));
					dropName = matcher2.group(2);
				}

				participants.add(player);
				this.logAndClearKill(player, dropName, qty);

				return;
			}

			Matcher matcher = DROP_MESSAGE.matcher(chatMessage.getMessage());
			log.info("Chat message: " + chatMessage.getMessage());

			// TODO - Handle gem drop and other multi-item drops
			if (matcher.lookingAt()) {
				int qty = 1; // Default quantity
				String player = matcher.group(1);
				String dropName = matcher.group(2);

				Matcher matcher2 = DROP_QTY.matcher(dropName);
				if (matcher2.lookingAt()) {
					qty = Integer.parseInt(matcher2.group(1).replaceAll(",", ""));
					dropName = matcher2.group(2);
				}

				// Make sure the person who got the kill is in the list
				participants.add(player);

				this.logAndClearKill(player, dropName, qty);
			}
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged interactingChanged) {
		Actor source = interactingChanged.getSource();
		Actor target = interactingChanged.getTarget();

		if (this.corp == null || target != this.corp) {
			return;
		}

		log.info(String.format("Adding: %s", source.getName()));

		this.participants.add(source.getName());
		this.createAndLogNewKillIfNotPresent();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (gameStateChanged.getGameState() != this.lastGameState
				&& (gameStateChanged.getGameState() == GameState.LOGGED_IN
				|| gameStateChanged.getGameState() == GameState.LOGIN_SCREEN || gameStateChanged.getGameState() == GameState.HOPPING)) {
			// Clear exiting state
			log.info("Game State Changed: " + gameStateChanged.getGameState().toString());
			this.lastGameState = gameStateChanged.getGameState();
			this.clearNpcActiveKill(0);
		}
	}

	@Provides
	CorpEventTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(CorpEventTrackerConfig.class);
	}

	private void preFillKills() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() == GameState.STARTING || client.getGameState() == GameState.UNKNOWN) {
				return false;
			}
			executor.submit(() -> {
				clientThread.invokeLater(() -> {
					generateKills();
					SwingUtilities.invokeLater(() -> panel.rebuild());
				});
			});
			return true;
		});
	}

	private void generateKills() {
		this.participants.add("steven10172");
		this.participants.add("player 2");
		this.participants.add("player 3");

		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Pure Essence", 1200);

		this.participants.add("player 4");
		this.participants.add("player 5");
		this.participants.add("player 6");
		this.participants.add("player 7");
		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Elysian Sigil", 1);

		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Spirit Shield", 1);

		this.participants.add("player 8");
		this.participants.add("player 9");
		this.participants.add("player 10");
		this.participants.add("player 11");
		this.participants.add("player 12");
		this.participants.add("player 13");
		this.participants.add("player 14");
		this.participants.add("player 15");
		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Holy elixir", 1);

		this.participants.clear();
		this.participants.add("player 1");
		this.participants.add("player 2");
		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Onyx bolts (e)", 175);

		this.killId = UUID.randomUUID();
		logAndClearKill("Steven10172", "Coins", 35379);

		this.participants.clear();
		this.killId = UUID.randomUUID();
		logInProgressKill();

		this.killId = null;
		this.participants.clear();
	}

	private void logInProgressKill() {
		if (!this.config.showInProgress()) {
			return;
		}
		BossKillEvent bossKill = generateBossKill("IN-PROGRESS", BossTrackerItem.generateFakeItem());
		bossKill.setInProgress(true);
		log.info("In Progress: " + bossKill.getUuid().toString());

		SwingUtilities.invokeLater(() -> this.panel.updateRecord(bossKill));
	}

	private void logAndClearKill(String killOwner, String dropName, int qty) {
		BossKillEvent bossKill = this.generateBossKill(killOwner, dropName, qty);
		log.info("Kill Completed: " + bossKill.getUuid().toString());

		SwingUtilities.invokeLater(() -> this.panel.updateRecord(bossKill));

		this.killId = null;
		this.clearBossExpireThread();
	}

	private void clearBossExpireThread() {
		if (this.bosskillExpiredThread != null) {
			log.info("Killing expiration check thread");
			this.bosskillExpiredThread.interrupt();
			this.bosskillExpiredThread = null;
		}
	}

	private BossKillEvent generateBossKill(String killOwner, BossTrackerItem droppedItem) {
		return this.generateBossKill(killOwner, droppedItem, NpcID.CORPOREAL_BEAST);
	}

	private BossKillEvent generateBossKill(String killOwner, BossTrackerItem droppedItem, int bossId) {
		return new BossKillEvent(this.killId, bossId, droppedItem, killOwner, this.convertParticipantsToList(), Instant.now());
	}

	private BossKillEvent generateBossKill(String killOwner, String dropName, int qty) {
		BossTrackerItem droppedItem = this.generateDrop(dropName, qty);
		return this.generateBossKill(killOwner, droppedItem);
	}

	private List<String> convertParticipantsToList() {
		return new ArrayList<>(this.participants);
	}

	private BossTrackerItem generateDrop(String dropName, int qty) {
		if (dropName.toLowerCase().equals("coins")) {
			log.info("Coins received");
			return new BossTrackerItem(ItemID.COINS_995, "Coins", qty, 1, 1);
		}

		ItemPrice item = findItem(dropName);
		long storePrice = itemManager.getItemComposition(item.getId()).getPrice();
		int alchPrice = Math.round(storePrice * Constants.HIGH_ALCHEMY_MULTIPLIER);

		return new BossTrackerItem(item.getId(), item.getName(), qty, item.getPrice(), alchPrice);
	}

	private ItemPrice findItem(String dropName) {
		List<ItemPrice> items = itemManager.search(dropName);
		for (ItemPrice item : items) {
			if (item.getName().toLowerCase().equals(dropName.toLowerCase())) {
				return item;
			}
		}

		return null;
	}

	private void renderBossIcon(final int itemID, final int width, final int height) {

		AsyncBufferedImage icon = itemManager.getImage(itemID);
		Runnable resize = () -> {
			// Scale the image
			Image scaled = icon.getScaledInstance(width, height, Image.SCALE_SMOOTH);

			// Load the image into the buffer
			BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);

			// Generate and draw the image
			Graphics2D g2 = bi.createGraphics();
			g2.drawImage(scaled, 0, 0, null);
			g2.dispose();

			panel.loadHeaderIcon(bi);
		};
		icon.onLoaded(resize);
		resize.run();
	}

	private static Thread setTimeout(Runnable runnable, int delay) {
		log.info("Setting up another waiting thread");
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(delay);
				log.info("Still executing thread");
				runnable.run();
			} catch (Exception e) {
				System.err.println(e);
			}
		});

		t.start();

		return t;
	}
}
