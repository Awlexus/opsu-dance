/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.states;

import itdelatrisu.opsu.*;
import itdelatrisu.opsu.GameData.Grade;
import itdelatrisu.opsu.audio.MultiClip;
import itdelatrisu.opsu.audio.MusicController;
import itdelatrisu.opsu.audio.SoundController;
import itdelatrisu.opsu.audio.SoundEffect;
import itdelatrisu.opsu.beatmap.Beatmap;
import itdelatrisu.opsu.beatmap.BeatmapDifficultyCalculator;
import itdelatrisu.opsu.beatmap.BeatmapGroup;
import itdelatrisu.opsu.beatmap.BeatmapParser;
import itdelatrisu.opsu.beatmap.BeatmapSet;
import itdelatrisu.opsu.beatmap.BeatmapSetList;
import itdelatrisu.opsu.beatmap.BeatmapSetNode;
import itdelatrisu.opsu.beatmap.BeatmapSortOrder;
import itdelatrisu.opsu.beatmap.BeatmapWatchService;
import itdelatrisu.opsu.beatmap.BeatmapWatchService.BeatmapWatchServiceListener;
import itdelatrisu.opsu.beatmap.LRUCache;
import itdelatrisu.opsu.beatmap.OszUnpacker;
import itdelatrisu.opsu.db.BeatmapDB;
import itdelatrisu.opsu.db.ScoreDB;
import itdelatrisu.opsu.states.ButtonMenu.MenuState;
import itdelatrisu.opsu.ui.Colors;
import itdelatrisu.opsu.ui.DropdownMenu;
import itdelatrisu.opsu.ui.Fonts;
import itdelatrisu.opsu.ui.KineticScrolling;
import itdelatrisu.opsu.ui.MenuButton;
import itdelatrisu.opsu.ui.StarStream;
import itdelatrisu.opsu.ui.UI;
import itdelatrisu.opsu.ui.animations.AnimatedValue;
import itdelatrisu.opsu.ui.animations.AnimationEquation;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent.Kind;
import java.util.Map;
import java.util.Stack;

import org.newdawn.slick.Animation;
import org.newdawn.slick.Color;
import org.newdawn.slick.Graphics;
import org.newdawn.slick.Image;
import org.newdawn.slick.Input;
import org.newdawn.slick.SpriteSheet;
import org.newdawn.slick.gui.TextField;
import yugecin.opsudance.core.DisplayContainer;
import yugecin.opsudance.core.events.EventBus;
import yugecin.opsudance.core.inject.Inject;
import yugecin.opsudance.core.inject.InstanceContainer;
import yugecin.opsudance.core.state.ComplexOpsuState;
import yugecin.opsudance.events.BarNotificationEvent;
import yugecin.opsudance.options.Configuration;
import yugecin.opsudance.options.OptionGroups;
import yugecin.opsudance.ui.OptionsOverlay;

import static yugecin.opsudance.options.Options.*;

/**
 * "Song Selection" state.
 * <p>
 * Players are able to select a beatmap to play, view previous scores, choose game mods,
 * manage beatmaps, or change game options from this state.
 */
public class SongMenu extends ComplexOpsuState {

	@Inject
	private InstanceContainer instanceContainer;

	@Inject
	private Configuration config;

	@Inject
	private OszUnpacker oszUnpacker;

	@Inject
	private BeatmapParser beatmapParser;

	/** The max number of song buttons to be shown on each screen. */
	public static final int MAX_SONG_BUTTONS = 6;

	/** The max number of score buttons to be shown at a time. */
	public static final int MAX_SCORE_BUTTONS = 7;

	/** Delay time, in milliseconds, between each search. */
	private static final int SEARCH_DELAY = 500;

	/** Delay time, in milliseconds, before moving to the beatmap menu after a right click. */
	private static final int BEATMAP_MENU_DELAY = 600;

	/** Maximum x offset of song buttons for mouse hover, in pixels. */
	private static final float MAX_HOVER_OFFSET = 30f;

	/** Time, in milliseconds, for the search bar to fade in or out. */
	private static final int SEARCH_TRANSITION_TIME = 250;

	/** Line width of the header/footer divider. */
	private static final int DIVIDER_LINE_WIDTH = 4;

	/** Song node class representing an BeatmapSetNode and file index. */
	private static class SongNode {
		/** Song node. */
		private BeatmapSetNode node;

		/** File index. */
		private int index;

		/**
		 * Constructor.
		 * @param node the BeatmapSetNode
		 * @param index the file index
		 */
		public SongNode(BeatmapSetNode node, int index) {
			this.node = node;
			this.index = index;
		}

		/**
		 * Returns the associated BeatmapSetNode.
		 */
		public BeatmapSetNode getNode() { return node; }

		/**
		 * Returns the associated file index.
		 */
		public int getIndex() { return index; }
	}

	/** Current start node (topmost menu entry). */
	private BeatmapSetNode startNode;

	/** The first node is about this high above the header. */
	private KineticScrolling songScrolling = new KineticScrolling();

	/** The number of Nodes to offset from the top to the startNode. */
	private int startNodeOffset;

	/** Current focused (selected) node. */
	private BeatmapSetNode focusNode;

	/** The base node of the previous focus node. */
	private SongNode oldFocusNode = null;

	/** Stack of previous "random" (F2) focus nodes. */
	private Stack<SongNode> randomStack = new Stack<SongNode>();

	/** Current focus node's song information. */
	private String[] songInfo;

	/** Button coordinate values. */
	private float buttonX, buttonY, buttonOffset, buttonWidth, buttonHeight;

	/** Horizontal offset of song buttons for mouse hover, in pixels. */
	private AnimatedValue hoverOffset = new AnimatedValue(250, 0, MAX_HOVER_OFFSET, AnimationEquation.OUT_QUART);

	/** Current index of hovered song button. */
	private BeatmapSetNode hoverIndex = null;

	/** The selection buttons. */
	private MenuButton selectModsButton, selectRandomButton, selectMapOptionsButton, selectOptionsButton;

	/** The search textfield. */
	private TextField searchTextField;

	/**
	 * Delay timer, in milliseconds, before running another search.
	 * This is overridden by character entry (reset) and 'esc' (immediate search).
	 */
	private int searchTimer = 0;

	/** Information text to display based on the search query. */
	private String searchResultString = null;

	/** Loader animation. */
	private Animation loader;

	/** Whether or not to reset game data upon entering the state. */
	private boolean resetGame = false;

	/** Whether or not to reset music track upon entering the state. */
	private boolean resetTrack = false;

	/** If non-null, determines the action to perform upon entering the state. */
	private MenuState stateAction;

	/** If non-null, the node that stateAction acts upon. */
	private BeatmapSetNode stateActionNode;

	/** If non-null, the score data that stateAction acts upon. */
	private ScoreData stateActionScore;

	/** Timer before moving to the beatmap menu with the current focus node. */
	private int beatmapMenuTimer = -1;

	/** Beatmap reloading thread. */
	private BeatmapReloadThread reloadThread;

	/** Thread for reloading beatmaps. */
	private class BeatmapReloadThread extends Thread {
		/** If true, also clear the beatmap cache and invoke the unpacker. */
		private final boolean fullReload;

		/** Whether this thread has completed execution. */
		private boolean finished = false;

		/** Returns true only if this thread has completed execution. */
		public boolean isFinished() { return finished; }

		/**
		 * Constructor.
		 * @param fullReload if true, also clear the beatmap cache and invoke the unpacker
		 */
		public BeatmapReloadThread(boolean fullReload) {
			this.fullReload = fullReload;
		}

		@Override
		public void run() {
			try {
				reloadBeatmaps();
			} finally {
				finished = true;
			}
		}

		/** Reloads all beatmaps. */
		private void reloadBeatmaps() {
			if (fullReload) {
				BeatmapDB.clearDatabase();
				oszUnpacker.unpackAll();
			}
			beatmapParser.parseAll();
		}
	}

	/** Current map of scores (Version, ScoreData[]). */
	private Map<String, ScoreData[]> scoreMap;

	/** Scores for the current focus node. */
	private ScoreData[] focusScores;

	/** Current start score (topmost score entry). */
	private KineticScrolling startScorePos = new KineticScrolling();

	/** Header and footer end and start y coordinates, respectively. */
	private float headerY, footerY;

	/** Footer pulsing logo button. */
	private MenuButton footerLogoButton;

	/** Size of the pulsing logo in the footer. */
	private float footerLogoSize;

	/** Time, in milliseconds, for fading the search bar. */
	private int searchTransitionTimer = SEARCH_TRANSITION_TIME;

	/** The text length of the last string in the search TextField. */
	private int lastSearchTextLength = -1;

	/** Whether the song folder changed (notified via the watch service). */
	private boolean songFolderChanged = false;

	/** The last background image. */
	private File lastBackgroundImage;

	/** Background alpha level (for fade-in effect). */
	private AnimatedValue bgAlpha = new AnimatedValue(800, 0f, 1f, AnimationEquation.OUT_QUAD);

	/** Timer for animations when a new song node is selected. */
	private AnimatedValue songChangeTimer = new AnimatedValue(900, 0f, 1f, AnimationEquation.LINEAR);

	/** Timer for the music icon animation when a new song node is selected. */
	private AnimatedValue musicIconBounceTimer = new AnimatedValue(350, 0f, 1f, AnimationEquation.LINEAR);

	/**
	 * Beatmaps whose difficulties were recently computed (if flag is non-null).
	 * Unless the Boolean flag is null, then upon removal, the beatmap's objects will
	 * be cleared (to be garbage collected). If the flag is true, also clear the
	 * beatmap's array fields (timing points, etc.).
	 */
	@SuppressWarnings("serial")
	private LRUCache<Beatmap, Boolean> beatmapsCalculated = new LRUCache<Beatmap, Boolean>(12) {
		@Override
		public void eldestRemoved(Map.Entry<Beatmap, Boolean> eldest) {
			Boolean b = eldest.getValue();
			if (b != null) {
				Beatmap beatmap = eldest.getKey();
				beatmap.objects = null;
				if (b) {
					beatmap.timingPoints = null;
					beatmap.breaks = null;
					beatmap.combo = null;
				}
			}
		}
	};

	/** The star stream. */
	private StarStream starStream;

	/** The maximum number of stars in the star stream. */
	private static final int MAX_STREAM_STARS = 20;

	/** Whether the menu is currently scrolling to the focus node (blocks other actions). */
	private boolean isScrollingToFocusNode = false;

	/** Sort order dropdown menu. */
	private DropdownMenu<BeatmapSortOrder> sortMenu;

	private final OptionsOverlay optionsOverlay;

	public SongMenu(DisplayContainer displayContainer) {
		super();
		optionsOverlay = new OptionsOverlay(displayContainer, OptionGroups.normalOptions);
		overlays.add(optionsOverlay);
	}

	@Override
	public void revalidate() {
		super.revalidate();

		components.clear();

		// header/footer coordinates
		headerY = displayContainer.height * 0.0075f + GameImage.MENU_MUSICNOTE.getImage().getHeight() +
				Fonts.BOLD.getLineHeight() + Fonts.DEFAULT.getLineHeight() +
				Fonts.SMALL.getLineHeight();
		footerY = displayContainer.height - GameImage.SELECTION_MODS.getImage().getHeight();

		// footer logo coordinates
		float footerHeight = displayContainer.height - footerY;
		footerLogoSize = footerHeight * 3.25f;
		Image logo = GameImage.MENU_LOGO.getImage();
		logo = logo.getScaledCopy(footerLogoSize / logo.getWidth());
		footerLogoButton = new MenuButton(logo, displayContainer.width - footerHeight * 0.8f, displayContainer.height - footerHeight * 0.65f);
		footerLogoButton.setHoverAnimationDuration(1);
		footerLogoButton.setHoverExpand(1.2f);

		// initialize sorts
		int sortWidth = (int) (displayContainer.width * 0.12f);
		int posX = (int) (displayContainer.width * 0.87f);
		int posY = (int) (headerY - GameImage.MENU_TAB.getImage().getHeight() * 2.25f);
		sortMenu = new DropdownMenu<BeatmapSortOrder>(displayContainer, BeatmapSortOrder.values(), posX, posY, sortWidth) {
			@Override
			public void itemSelected(int index, BeatmapSortOrder item) {
				BeatmapSortOrder.set(item);
				if (focusNode == null)
					return;
				BeatmapSetNode oldFocusBase = BeatmapSetList.get().getBaseNode(focusNode.index);
				int oldFocusFileIndex = focusNode.beatmapIndex;
				focusNode = null;
				BeatmapSetList.get().init();
				SongMenu.this.setFocus(oldFocusBase, oldFocusFileIndex, true, true);
			}

			@Override
			public boolean canSelect(int index) {
				if (isInputBlocked())
					return false;

				SoundController.playSound(SoundEffect.MENUCLICK);
				return true;
			}
		};
		sortMenu.setBackgroundColor(Colors.BLACK_BG_HOVER);
		sortMenu.setBorderColor(Colors.BLUE_DIVIDER);
		sortMenu.setChevronRightColor(Color.white);
		components.add(sortMenu);

		// initialize group tabs
		for (BeatmapGroup group : BeatmapGroup.values())
			group.init(displayContainer.width, headerY - DIVIDER_LINE_WIDTH / 2);

		// initialize score data buttons
		ScoreData.init(displayContainer.width, headerY + displayContainer.height * 0.01f);

		// song button background & graphics context
		Image menuBackground = GameImage.MENU_BUTTON_BG.getImage();

		// song button coordinates
		buttonX = displayContainer.width * 0.6f;
		//buttonY = headerY;
		buttonWidth = menuBackground.getWidth();
		buttonHeight = menuBackground.getHeight();
		buttonOffset = (footerY - headerY - DIVIDER_LINE_WIDTH) / MAX_SONG_BUTTONS;

		// search
		int textFieldX = (int) (displayContainer.width * 0.7125f + Fonts.BOLD.getWidth("Search: "));
		int textFieldY = (int) (headerY + Fonts.BOLD.getLineHeight() / 2);
		searchTextField = new TextField(displayContainer, Fonts.BOLD, textFieldX, textFieldY, (int) (displayContainer.width * 0.99f) - textFieldX, Fonts.BOLD.getLineHeight()) {
			@Override
			public boolean isFocusable() {
				return false;
			}
		};
		searchTextField.setBackgroundColor(Color.transparent);
		searchTextField.setBorderColor(Color.transparent);
		searchTextField.setTextColor(Color.white);
		searchTextField.setMaxLength(60);
		searchTextField.setFocused(true);
		components.add(searchTextField);

		// selection buttons
		Image selectionMods = GameImage.SELECTION_MODS.getImage();
		int selectButtonsWidth = selectionMods.getWidth();
		int selectButtonsHeight = selectionMods.getHeight();
		if (selectButtonsHeight < 20) {
			selectButtonsHeight = 100;
		}
		if (selectButtonsWidth < 20) {
			selectButtonsWidth = 100;
		}
		float selectX = displayContainer.width * 0.183f + selectButtonsWidth / 2f;
		float selectY = displayContainer.height - selectButtonsHeight / 2f;
		float selectOffset = selectButtonsWidth * 1.05f;
		selectModsButton = new MenuButton(GameImage.SELECTION_MODS_OVERLAY.getImage(),
				selectX, selectY);
		selectRandomButton = new MenuButton(GameImage.SELECTION_RANDOM_OVERLAY.getImage(),
				selectX + selectOffset, selectY);
		selectMapOptionsButton = new MenuButton(GameImage.SELECTION_OPTIONS_OVERLAY.getImage(),
				selectX + selectOffset * 2f, selectY);
		selectOptionsButton = new MenuButton(GameImage.SELECTION_OTHER_OPTIONS_OVERLAY.getImage(),
				selectX + selectOffset * 3f, selectY);
		selectModsButton.setHoverFade(0f);
		selectRandomButton.setHoverFade(0f);
		selectMapOptionsButton.setHoverFade(0f);
		selectOptionsButton.setHoverFade(0f);

		// loader
		int loaderDim = GameImage.MENU_MUSICNOTE.getImage().getWidth();
		SpriteSheet spr = new SpriteSheet(GameImage.MENU_LOADER.getImage(), loaderDim, loaderDim);
		loader = new Animation(spr, 50);

		// beatmap watch service listener
		BeatmapWatchService.addListener(new BeatmapWatchServiceListener() {
			@Override
			public void eventReceived(Kind<?> kind, Path child) {
				if (!songFolderChanged && kind != StandardWatchEventKinds.ENTRY_MODIFY) {
					songFolderChanged = true;
					if (displayContainer.isInState(SongMenu.class)) {
						EventBus.post(new BarNotificationEvent("Changed is Songs folder detected. Hit F5 to refresh."));
					}
				}
			}
		});

		// star stream
		starStream = new StarStream(displayContainer.width, (displayContainer.height - GameImage.STAR.getImage().getHeight()) / 2, -displayContainer.width, 0, MAX_STREAM_STARS);
		starStream.setPositionSpread(displayContainer.height / 20f);
		starStream.setDirectionSpread(10f);
	}

	@Override
	public void render(Graphics g) {
		g.setBackground(Color.black);

		int width = displayContainer.width;
		int height = displayContainer.height;
		int mouseX = displayContainer.mouseX;
		int mouseY = displayContainer.mouseY;

		// background
		if (focusNode != null) {
			Beatmap focusNodeBeatmap = focusNode.getSelectedBeatmap();
			if (!focusNodeBeatmap.drawBackground(width, height, bgAlpha.getValue(), true))
				GameImage.PLAYFIELD.getImage().draw();
		}

		// star stream
		starStream.draw();

		// song buttons
		BeatmapSetNode node = startNode;
		int songButtonIndex = 0;
		if (node != null && node.prev != null) {
			node = node.prev;
			songButtonIndex = -1;
		}
		g.setClip(0, (int) (headerY + DIVIDER_LINE_WIDTH / 2), width, (int) (footerY - headerY));
		for (int i = startNodeOffset + songButtonIndex; i < MAX_SONG_BUTTONS + 1 && node != null; i++, node = node.next) {
			// draw the node
			float offset = (node == hoverIndex) ? hoverOffset.getValue() : 0f;
			float ypos = buttonY + (i * buttonOffset);
			float mid = (height / 2) - ypos - (buttonOffset / 2);
			final float circleRadi = 700 * GameImage.getUIscale();
			//finds points along a very large circle  (x^2 = h^2 - y^2)
			float t = circleRadi * circleRadi - (mid * mid);
			float xpos = (float) ((t > 0) ? Math.sqrt(t) : 0) - circleRadi + 50 * GameImage.getUIscale();
			ScoreData[] scores = getScoreDataForNode(node, false);
			node.draw(buttonX - offset - xpos, ypos,
			          (scores == null) ? Grade.NULL : scores[0].getGrade(), (node == focusNode));
		}
		g.clearClip();

		// scroll bar
		if (focusNode != null && startNode != null) {
			int focusNodes = focusNode.getBeatmapSet().size();
			int totalNodes = BeatmapSetList.get().size() + focusNodes - 1;
			if (totalNodes > MAX_SONG_BUTTONS) {
				UI.drawScrollbar(g,
						songScrolling.getPosition(),
						totalNodes * buttonOffset,
						MAX_SONG_BUTTONS * buttonOffset,
						width, headerY + DIVIDER_LINE_WIDTH / 2,
						0, MAX_SONG_BUTTONS * buttonOffset,
						Colors.BLACK_ALPHA, Color.white, true);
			}
		}

		// score buttons
		if (focusScores != null) {
			ScoreData.clipToArea(g);
			int startScore = (int) (startScorePos.getPosition() / ScoreData.getButtonOffset());
			int offset = (int) (-startScorePos.getPosition() + startScore * ScoreData.getButtonOffset());

			int scoreButtons = Math.min(focusScores.length - startScore, MAX_SCORE_BUTTONS + 1);
			float timerScale = 1f - (1 / 3f) * ((MAX_SCORE_BUTTONS - scoreButtons) / (float) (MAX_SCORE_BUTTONS - 1));
			int duration = (int) (songChangeTimer.getDuration() * timerScale);
			int segmentDuration = (int) ((2 / 3f) * songChangeTimer.getDuration());
			int time = songChangeTimer.getTime();
			for (int i = 0, rank = startScore; i < scoreButtons; i++, rank++) {
				if (rank < 0)
					continue;
				long prevScore = (rank + 1 < focusScores.length) ? focusScores[rank + 1].score : -1;
				float t = Utils.clamp((time - (i * (duration - segmentDuration) / scoreButtons)) / (float) segmentDuration, 0f, 1f);
				boolean focus = (t >= 0.9999f && ScoreData.buttonContains(mouseX, mouseY - offset, i));
				focusScores[rank].draw(g, offset + i * ScoreData.getButtonOffset(), rank, prevScore, focus, t);
			}
			g.clearClip();

			// scroll bar
			if (focusScores.length > MAX_SCORE_BUTTONS && ScoreData.areaContains(mouseX, mouseY) && !isAnyComponentFocused()) {
				ScoreData.drawScrollbar(g, startScorePos.getPosition(), focusScores.length * ScoreData.getButtonOffset());
			}
		}

		// top/bottom bars
		g.setColor(Colors.BLACK_ALPHA);
		g.fillRect(0, 0, width, headerY);
		g.fillRect(0, footerY, width, height - footerY);
		g.setColor(Colors.BLUE_DIVIDER);
		g.setLineWidth(DIVIDER_LINE_WIDTH);
		g.drawLine(0, headerY, width, headerY);
		g.drawLine(0, footerY, width, footerY);
		g.resetLineWidth();

		// footer logo (pulsing)
		Float position = MusicController.getBeatProgress();
		if (position == null)  // default to 60bpm
			position = System.currentTimeMillis() % 1000 / 1000f;
		if (footerLogoButton.contains(mouseX, mouseY, 0.25f)) {
			// hovering over logo: stop pulsing
			footerLogoButton.draw();
		} else {
			float expand = position * 0.15f;
			footerLogoButton.draw(Color.white, 1f - expand);
			Image ghostLogo = GameImage.MENU_LOGO.getImage();
			ghostLogo = ghostLogo.getScaledCopy((1f + expand) * footerLogoSize / ghostLogo.getWidth());
			float oldGhostAlpha = Colors.GHOST_LOGO.a;
			Colors.GHOST_LOGO.a *= (1f - position);
			ghostLogo.drawCentered(footerLogoButton.getX(), footerLogoButton.getY(), Colors.GHOST_LOGO);
			Colors.GHOST_LOGO.a = oldGhostAlpha;
		}

		// header
		if (focusNode != null) {
			// music/loader icon
			float marginX = width * 0.005f, marginY = height * 0.005f;
			Image musicNote = GameImage.MENU_MUSICNOTE.getImage();
			if (MusicController.isTrackLoading())
				loader.draw(marginX, marginY);
			else {
				float t = musicIconBounceTimer.getValue() * 2f;
				if (t > 1)
					t = 2f - t;
				float musicNoteScale = 1f + 0.3f * t;
				musicNote.getScaledCopy(musicNoteScale).drawCentered(marginX + musicNote.getWidth() / 2f, marginY + musicNote.getHeight() / 2f);
			}
			int iconWidth = musicNote.getWidth();

			// song info text
			if (songInfo == null) {
				songInfo = focusNode.getInfo();
				if (OPTION_SHOW_UNICODE.state) {
					Beatmap beatmap = focusNode.getBeatmapSet().get(0);
					Fonts.loadGlyphs(Fonts.LARGE, beatmap.titleUnicode);
					Fonts.loadGlyphs(Fonts.LARGE, beatmap.artistUnicode);
				}
			}
			marginX += 5;
			Color c = Colors.WHITE_FADE;
			float oldAlpha = c.a;
			float t = AnimationEquation.OUT_QUAD.calc(songChangeTimer.getValue());
			float headerTextY = marginY * 0.2f;
			c.a = Math.min(t * songInfo.length / 1.5f, 1f);
			if (c.a > 0)
				Fonts.LARGE.drawString(marginX + iconWidth * 1.05f, headerTextY, songInfo[0], c);
			headerTextY += Fonts.LARGE.getLineHeight() - 6;
			c.a = Math.min((t - 1f / (songInfo.length * 1.5f)) * songInfo.length / 1.5f, 1f);
			if (c.a > 0)
				Fonts.DEFAULT.drawString(marginX + iconWidth * 1.05f, headerTextY, songInfo[1], c);
			headerTextY += Fonts.DEFAULT.getLineHeight() - 2;
			c.a = Math.min((t - 2f / (songInfo.length * 1.5f)) * songInfo.length / 1.5f, 1f);
			if (c.a > 0) {
				float speedModifier = GameMod.getSpeedMultiplier();
				Color color2 = (speedModifier == 1f) ? c :
					(speedModifier > 1f) ? Colors.RED_HIGHLIGHT : Colors.BLUE_HIGHLIGHT;
				float oldAlpha2 = color2.a;
				color2.a = c.a;
				Fonts.BOLD.drawString(marginX, headerTextY, songInfo[2], color2);
				color2.a = oldAlpha2;
			}
			headerTextY += Fonts.BOLD.getLineHeight() - 4;
			c.a = Math.min((t - 3f / (songInfo.length * 1.5f)) * songInfo.length / 1.5f, 1f);
			if (c.a > 0)
				Fonts.DEFAULT.drawString(marginX, headerTextY, songInfo[3], c);
			headerTextY += Fonts.DEFAULT.getLineHeight() - 4;
			c.a = Math.min((t - 4f / (songInfo.length * 1.5f)) * songInfo.length / 1.5f, 1f);
			if (c.a > 0) {
				float multiplier = GameMod.getDifficultyMultiplier();
				Color color4 = (multiplier == 1f) ? c :
					(multiplier > 1f) ? Colors.RED_HIGHLIGHT : Colors.BLUE_HIGHLIGHT;
				float oldAlpha4 = color4.a;
				color4.a = c.a;
				Fonts.SMALL.drawString(marginX, headerTextY, songInfo[4], color4);
				color4.a = oldAlpha4;
			}
			c.a = oldAlpha;
		}

		// selection buttons
		GameImage.SELECTION_MODS.getImage().drawCentered(selectModsButton.getX(), selectModsButton.getY());
		selectModsButton.draw();
		GameImage.SELECTION_RANDOM.getImage().drawCentered(selectRandomButton.getX(), selectRandomButton.getY());
		selectRandomButton.draw();
		GameImage.SELECTION_OPTIONS.getImage().drawCentered(selectMapOptionsButton.getX(), selectMapOptionsButton.getY());
		selectMapOptionsButton.draw();
		GameImage.SELECTION_OTHER_OPTIONS.getImage().drawCentered(selectOptionsButton.getX(), selectOptionsButton.getY());
		selectOptionsButton.draw();

		// group tabs
		BeatmapGroup currentGroup = BeatmapGroup.current();
		BeatmapGroup hoverGroup = null;
		if (!isAnyComponentFocused()) {
			for (BeatmapGroup group : BeatmapGroup.values()) {
				if (group.contains(mouseX, mouseY)) {
					hoverGroup = group;
					break;
				}
			}
		}
		for (BeatmapGroup group : BeatmapGroup.VALUES_REVERSED) {
			if (group != currentGroup)
				group.draw(false, group == hoverGroup);
		}
		currentGroup.draw(true, false);

		// search
		boolean searchEmpty = searchTextField.getText().isEmpty();
		int searchX = searchTextField.x;
		int searchY = searchTextField.y;
		float searchBaseX = width * 0.7f;
		float searchTextX = width * 0.7125f;
		float searchRectHeight = Fonts.BOLD.getLineHeight() * 2;
		float searchExtraHeight = Fonts.DEFAULT.getLineHeight() * 0.7f;
		float searchProgress = (searchTransitionTimer < SEARCH_TRANSITION_TIME) ?
				((float) searchTransitionTimer / SEARCH_TRANSITION_TIME) : 1f;
		float oldAlpha = Colors.BLACK_ALPHA.a;
		if (searchEmpty) {
			searchRectHeight += (1f - searchProgress) * searchExtraHeight;
			Colors.BLACK_ALPHA.a = 0.5f - searchProgress * 0.3f;
		} else {
			searchRectHeight += searchProgress * searchExtraHeight;
			Colors.BLACK_ALPHA.a = 0.2f + searchProgress * 0.3f;
		}
		g.setColor(Colors.BLACK_ALPHA);
		g.fillRect(searchBaseX, headerY + DIVIDER_LINE_WIDTH / 2, width - searchBaseX, searchRectHeight);
		Colors.BLACK_ALPHA.a = oldAlpha;
		Fonts.BOLD.drawString(searchTextX, searchY, "Search:", Colors.GREEN_SEARCH);
		if (searchEmpty) {
			Fonts.BOLD.drawString(searchX, searchY, "Type to search!", Color.white);
		} else {
			g.setColor(Color.white);
			searchTextField.render(g);
			Fonts.DEFAULT.drawString(searchTextX, searchY + Fonts.BOLD.getLineHeight(), (searchResultString == null) ? "Searching..." : searchResultString, Color.white);
		}

		sortMenu.render(g);

		// reloading beatmaps
		if (reloadThread != null) {
			// darken the screen
			g.setColor(Colors.BLACK_ALPHA);
			g.fillRect(0, 0, width, height);

			UI.drawLoadingProgress(g);
		}

		// back button
		else
			UI.getBackButton().draw(g);

		UI.draw(g);

		super.render(g);
	}

	@Override
	public void preRenderUpdate() {
		super.preRenderUpdate();

		int delta = displayContainer.renderDelta;
		UI.update(delta);
		if (reloadThread == null)
			MusicController.loopTrackIfEnded(true);
		else if (reloadThread.isFinished()) {
			BeatmapGroup.set(BeatmapGroup.ALL);
			BeatmapSortOrder.set(BeatmapSortOrder.TITLE);
			BeatmapSetList.get().reset();
			BeatmapSetList.get().init();
			if (BeatmapSetList.get().size() > 0) {
				// initialize song list
				setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);
			} else
				MusicController.playThemeSong(config.themeBeatmap);
			reloadThread = null;
		}
		int mouseX = displayContainer.mouseX;
		int mouseY = displayContainer.mouseY;
		UI.getBackButton().hoverUpdate(delta, mouseX, mouseY);
		selectModsButton.hoverUpdate(delta, mouseX, mouseY);
		selectRandomButton.hoverUpdate(delta, mouseX, mouseY);
		selectMapOptionsButton.hoverUpdate(delta, mouseX, mouseY);
		selectOptionsButton.hoverUpdate(delta, mouseX, mouseY);
		footerLogoButton.hoverUpdate(delta, mouseX, mouseY, 0.25f);

		// beatmap menu timer
		if (beatmapMenuTimer > -1) {
			beatmapMenuTimer += delta;
			if (beatmapMenuTimer >= BEATMAP_MENU_DELAY) {
				beatmapMenuTimer = -1;
				if (focusNode != null) {
					MenuState state = focusNode.getBeatmapSet().isFavorite() ?
						MenuState.BEATMAP_FAVORITE : MenuState.BEATMAP;
					instanceContainer.provide(ButtonMenu.class).setMenuState(state, focusNode);
					displayContainer.switchState(ButtonMenu.class);
				}
				return;
			}
		}

		if (focusNode != null) {
			// fade in background
			Beatmap focusNodeBeatmap = focusNode.getSelectedBeatmap();
			if (!focusNodeBeatmap.isBackgroundLoading())
				bgAlpha.update(delta);

			// song change timers
			songChangeTimer.update(delta);
			if (!MusicController.isTrackLoading())
				musicIconBounceTimer.update(delta);
		}

		// star stream
		starStream.update(delta);

		// search
		searchTimer += delta;
		if (searchTimer >= SEARCH_DELAY && reloadThread == null && beatmapMenuTimer == -1) {
			searchTimer = 0;

			// store the start/focus nodes
			if (focusNode != null)
				oldFocusNode = new SongNode(BeatmapSetList.get().getBaseNode(focusNode.index), focusNode.beatmapIndex);

			if (BeatmapSetList.get().search(searchTextField.getText())) {
				// reset song stack
				randomStack = new Stack<>();

				// empty search
				if (searchTextField.getText().isEmpty())
					searchResultString = null;

				// search produced new list: re-initialize it
				startNode = focusNode = null;
				scoreMap = null;
				focusScores = null;
				if (BeatmapSetList.get().size() > 0) {
					BeatmapSetList.get().init();
					if (searchTextField.getText().isEmpty()) {  // cleared search
						// use previous start/focus if possible
						if (oldFocusNode != null)
							setFocus(oldFocusNode.getNode(), oldFocusNode.getIndex(), true, true);
						else
							setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);
					} else {
						int size = BeatmapSetList.get().size();
						searchResultString = String.format("%d match%s found!",
								size, (size == 1) ? "" : "es");
						setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);
					}
					oldFocusNode = null;
				} else if (!searchTextField.getText().isEmpty())
					searchResultString = "No matches found. Hit ESC to reset.";
			}
		}
		if (searchTransitionTimer < SEARCH_TRANSITION_TIME) {
			searchTransitionTimer += delta;
			if (searchTransitionTimer > SEARCH_TRANSITION_TIME)
				searchTransitionTimer = SEARCH_TRANSITION_TIME;
		}

		// scores
		if (focusScores != null) {
			startScorePos.setMinMax(0, (focusScores.length - MAX_SCORE_BUTTONS) * ScoreData.getButtonOffset());
			startScorePos.update(delta);
		}

		// scrolling
		songScrolling.update(delta);
		if (isScrollingToFocusNode) {
			float distanceDiff = Math.abs(songScrolling.getPosition() - songScrolling.getTargetPosition());
			if (distanceDiff <= buttonOffset / 8f) {  // close enough, stop blocking input
				songScrolling.scrollToPosition(songScrolling.getTargetPosition());
				songScrolling.setSpeedMultiplier(1f);
				isScrollingToFocusNode = false;
			}
		}
		updateDrawnSongPosition();

		// mouse hover
		BeatmapSetNode node = getNodeAtPosition(mouseX, mouseY);
		if (node != null && !isAnyComponentFocused()) {
			if (node == hoverIndex)
				hoverOffset.update(delta);
			else {
				hoverIndex = node;
				hoverOffset.setTime(0);
			}
			return;
		} else {  // not hovered
			hoverOffset.setTime(0);
			hoverIndex = null;
		}

		// tooltips
		if (sortMenu.baseContains(mouseX, mouseY))
			UI.updateTooltip(delta, "Sort by...", false);
		else if (focusScores != null && ScoreData.areaContains(mouseX, mouseY)) {
			int startScore = (int) (startScorePos.getPosition() / ScoreData.getButtonOffset());
			int offset = (int) (-startScorePos.getPosition() + startScore * ScoreData.getButtonOffset());
			int scoreButtons = Math.min(focusScores.length - startScore, MAX_SCORE_BUTTONS);
			for (int i = 0, rank = startScore; i < scoreButtons; i++, rank++) {
				if (rank < 0)
					continue;
				if (ScoreData.buttonContains(mouseX, mouseY - offset, i)) {
					UI.updateTooltip(delta, focusScores[rank].getTooltipString(), true);
					break;
				}
			}
		}
	}

	@Override
	public boolean mousePressed(int button, int x, int y) {
		if (super.mousePressed(button, x, y)) {
			return true;
		}

		if (button == Input.MOUSE_MIDDLE_BUTTON) {
			return false;
		}

		if (isScrollingToFocusNode) {
			return true;
		}

		songScrolling.pressed();
		startScorePos.pressed();
		return true;
	}

	@Override
	public boolean mouseReleased(int button, int x, int y) {
		if (super.mouseReleased(button, x, y)) {
			return true;
		}

		if (button == Input.MOUSE_MIDDLE_BUTTON) {
			return false;
		}

		if (isScrollingToFocusNode) {
			return true;
		}

		songScrolling.released();
		startScorePos.released();

		if (isInputBlocked()) {
			return true;
		}

		if (UI.getBackButton().contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUBACK);
			displayContainer.switchState(MainMenu.class);
			return true;
		}

		// selection buttons
		if (selectModsButton.contains(x, y)) {
			this.keyPressed(Input.KEY_F1, '\0');
			return true;
		} else if (selectRandomButton.contains(x, y)) {
			this.keyPressed(Input.KEY_F2, '\0');
			return true;
		} else if (selectMapOptionsButton.contains(x, y)) {
			this.keyPressed(Input.KEY_F3, '\0');
			return true;
		} else if (selectOptionsButton.contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUHIT);
			optionsOverlay.show();
			return true;
		}

		// group tabs
		for (BeatmapGroup group : BeatmapGroup.values()) {
			if (group.contains(x, y)) {
				if (group != BeatmapGroup.current()) {
					BeatmapGroup.set(group);
					SoundController.playSound(SoundEffect.MENUCLICK);
					startNode = focusNode = null;
					oldFocusNode = null;
					randomStack = new Stack<SongNode>();
					songInfo = null;
					scoreMap = null;
					focusScores = null;
					searchTextField.setText("");
					searchTimer = SEARCH_DELAY;
					searchTransitionTimer = SEARCH_TRANSITION_TIME;
					searchResultString = null;
					BeatmapSetList.get().reset();
					BeatmapSetList.get().init();
					setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);

					if (BeatmapSetList.get().size() < 1 && group.getEmptyMessage() != null) {
						EventBus.post(new BarNotificationEvent(group.getEmptyMessage()));
					}
				}
				return true;
			}
		}

		if (focusNode == null) {
			return false;
		}

		// logo: start game
		if (footerLogoButton.contains(x, y, 0.25f)) {
			startGame();
			return true;
		}

		// song buttons
		BeatmapSetNode node = getNodeAtPosition(x, y);
		if (node != null) {
			int expandedIndex = BeatmapSetList.get().getExpandedIndex();
			int oldHoverOffsetTime = hoverOffset.getTime();
			BeatmapSetNode oldHoverIndex = hoverIndex;

			// clicked node is already expanded
			if (node.index == expandedIndex) {
				if (node.beatmapIndex == focusNode.beatmapIndex) {
					// if already focused, load the beatmap
					if (button != Input.MOUSE_RIGHT_BUTTON)
						startGame();
					else
						SoundController.playSound(SoundEffect.MENUCLICK);
				} else {
					// focus the node
					SoundController.playSound(SoundEffect.MENUCLICK);
					setFocus(node, 0, false, true);
				}
			}

			// clicked node is a new group
			else {
				SoundController.playSound(SoundEffect.MENUCLICK);
				setFocus(node, -1, false, true);
			}

			// restore hover data
			hoverOffset.setTime(oldHoverOffsetTime);
			hoverIndex = oldHoverIndex;

			// open beatmap menu
			if (button == Input.MOUSE_RIGHT_BUTTON)
				beatmapMenuTimer = (node.index == expandedIndex) ? BEATMAP_MENU_DELAY * 4 / 5 : 0;

			return true;
		}

		// score buttons
		if (focusScores != null && ScoreData.areaContains(x, y)) {
			int startScore = (int) (startScorePos.getPosition() / ScoreData.getButtonOffset());
			int offset = (int) (-startScorePos.getPosition() + startScore * ScoreData.getButtonOffset());
			int scoreButtons = Math.min(focusScores.length - startScore, MAX_SCORE_BUTTONS);
			for (int i = 0, rank = startScore; i < scoreButtons; i++, rank++) {
				if (ScoreData.buttonContains(x, y - offset, i)) {
					SoundController.playSound(SoundEffect.MENUHIT);
					if (button != Input.MOUSE_RIGHT_BUTTON) {
						// view score
						instanceContainer.provide(GameRanking.class).setGameData(instanceContainer.injectFields(new GameData(focusScores[rank], displayContainer.width, displayContainer.height)));
						displayContainer.switchState(GameRanking.class);
					} else {
						// score management
						instanceContainer.provide(ButtonMenu.class).setMenuState(MenuState.SCORE, focusScores[rank]);
						displayContainer.switchState(ButtonMenu.class);
					}
					return true;
				}
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(int key, char c) {
		if (super.keyPressed(key, c)) {
			return true;
		}

		// block input
		if ((reloadThread != null && key != Input.KEY_ESCAPE) || beatmapMenuTimer > -1 || isScrollingToFocusNode) {
			return true;
		}

		Input input = displayContainer.input;

		switch (key) {
		case Input.KEY_ESCAPE:
			if (reloadThread != null) {
				// beatmap reloading: stop parsing beatmaps by sending interrupt to BeatmapParser
				reloadThread.interrupt();
			} else if (!searchTextField.getText().isEmpty()) {
				// clear search text
				searchTextField.setText("");
				searchTimer = SEARCH_DELAY;
				searchTransitionTimer = 0;
				searchResultString = null;
			} else {
				// return to main menu
				SoundController.playSound(SoundEffect.MENUBACK);
				displayContainer.switchState(MainMenu.class);
			}
			return true;
		case Input.KEY_F1:
			SoundController.playSound(SoundEffect.MENUHIT);
			instanceContainer.provide(ButtonMenu.class).setMenuState(MenuState.MODS);
			displayContainer.switchState(ButtonMenu.class);
			return true;
		case Input.KEY_F2:
			if (focusNode == null)
				break;
			SoundController.playSound(SoundEffect.MENUHIT);
			if (input.isKeyDown(Input.KEY_RSHIFT) || input.isKeyDown(Input.KEY_LSHIFT)) {
				// shift key: previous random track
				SongNode prev;
				if (randomStack.isEmpty() || (prev = randomStack.pop()) == null)
					break;
				BeatmapSetNode node = prev.getNode();
				int expandedIndex = BeatmapSetList.get().getExpandedIndex();
				if (node.index == expandedIndex)
					node = node.next;  // move past base node
				setFocus(node, prev.getIndex(), true, true);
			} else {
				// random track, add previous to stack
				randomStack.push(new SongNode(BeatmapSetList.get().getBaseNode(focusNode.index), focusNode.beatmapIndex));
				setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);
			}
			return true;
		case Input.KEY_F3:
			if (focusNode == null)
				break;
			SoundController.playSound(SoundEffect.MENUHIT);
			MenuState state = focusNode.getBeatmapSet().isFavorite() ?
				MenuState.BEATMAP_FAVORITE : MenuState.BEATMAP;
			instanceContainer.provide(ButtonMenu.class).setMenuState(state, focusNode);
			displayContainer.switchState(ButtonMenu.class);
			return true;
		case Input.KEY_F5:
			SoundController.playSound(SoundEffect.MENUHIT);
			if (songFolderChanged)
				reloadBeatmaps(false);
			else {
				instanceContainer.provide(ButtonMenu.class).setMenuState(MenuState.RELOAD);
				displayContainer.switchState(ButtonMenu.class);
			}
			return true;
		case Input.KEY_DELETE:
			if (focusNode == null)
				break;
			if (input.isKeyDown(Input.KEY_RSHIFT) || input.isKeyDown(Input.KEY_LSHIFT)) {
				SoundController.playSound(SoundEffect.MENUHIT);
				MenuState ms = (focusNode.beatmapIndex == -1 || focusNode.getBeatmapSet().size() == 1) ?
						MenuState.BEATMAP_DELETE_CONFIRM : MenuState.BEATMAP_DELETE_SELECT;
				instanceContainer.provide(ButtonMenu.class).setMenuState(ms, focusNode);
				displayContainer.switchState(ButtonMenu.class);
			}
			return true;
		case Input.KEY_ENTER:
			if (focusNode == null)
				break;
			startGame();
			return true;
		case Input.KEY_DOWN:
			changeIndex(1);
			return true;
		case Input.KEY_UP:
			changeIndex(-1);
			return true;
		case Input.KEY_RIGHT:
			if (focusNode == null)
				break;
			BeatmapSetNode next = focusNode.next;
			if (next != null) {
				SoundController.playSound(SoundEffect.MENUCLICK);
				BeatmapSetNode oldStartNode = startNode;
				int oldHoverOffsetTime = hoverOffset.getTime();
				BeatmapSetNode oldHoverIndex = hoverIndex;
				setFocus(next, 0, false, true);
				if (startNode == oldStartNode) {
					hoverOffset.setTime(oldHoverOffsetTime);
					hoverIndex = oldHoverIndex;
				}
			}
			return true;
		case Input.KEY_LEFT:
			if (focusNode == null)
				break;
			BeatmapSetNode prev = focusNode.prev;
			if (prev != null) {
				SoundController.playSound(SoundEffect.MENUCLICK);
				BeatmapSetNode oldStartNode = startNode;
				int oldHoverOffsetTime = hoverOffset.getTime();
				BeatmapSetNode oldHoverIndex = hoverIndex;
				setFocus(prev, (prev.index == focusNode.index) ? 0 : prev.getBeatmapSet().size() - 1, false, true);
				if (startNode == oldStartNode) {
					hoverOffset.setTime(oldHoverOffsetTime);
					hoverIndex = oldHoverIndex;
				}
			}
			return true;
		case Input.KEY_NEXT:
			changeIndex(MAX_SONG_BUTTONS);
			return true;
		case Input.KEY_PRIOR:
			changeIndex(-MAX_SONG_BUTTONS);
			return true;
		}
		if (key == Input.KEY_O && (input.isKeyDown(Input.KEY_LCONTROL) || input.isKeyDown(Input.KEY_RCONTROL))) {
			optionsOverlay.show();
			return true;
		}
		// wait for user to finish typing
		// TODO: accept all characters (current conditions are from TextField class)
		if ((c > 31 && c < 127) || key == Input.KEY_BACK) {
			searchTimer = 0;
			searchTextField.keyPressed(key, c);
			int textLength = searchTextField.getText().length();
			if (lastSearchTextLength != textLength) {
				if (key == Input.KEY_BACK) {
					if (textLength == 0)
						searchTransitionTimer = 0;
				} else if (textLength == 1)
					searchTransitionTimer = 0;
				lastSearchTextLength = textLength;
			}
		}
		return true;
	}

	@Override
	public boolean mouseDragged(int oldx, int oldy, int newx, int newy) {
		if (super.mouseDragged(oldx, oldy, newx, newy)) {
			return true;
		}

		if (isInputBlocked()) {
			return true;
		}

		int diff = newy - oldy;
		if (diff == 0) {
			return false;
		}

		// check mouse button (right click scrolls faster on songs)
		int multiplier;
		if (displayContainer.input.isMouseButtonDown(Input.MOUSE_RIGHT_BUTTON)) {
			multiplier = 10;
		} else if (displayContainer.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)) {
			multiplier = 1;
		} else {
			return false;
		}

		if (focusScores != null && focusScores.length >= MAX_SCORE_BUTTONS && ScoreData.areaContains(oldx, oldy)) {
			startScorePos.dragged(-diff * multiplier);
		} else {
			songScrolling.dragged(-diff * multiplier);
		}
		return true;
	}

	@Override
	public boolean mouseWheelMoved(int newValue) {
		if (super.mouseWheelMoved(newValue)) {
			return true;
		}

		Input input = displayContainer.input;

		if (isInputBlocked()) {
			return true;
		}

		int shift = (newValue < 0) ? 1 : -1;
		int mouseX = input.getMouseX(), mouseY = input.getMouseY();

		// score buttons
		if (focusScores != null && focusScores.length >= MAX_SCORE_BUTTONS && ScoreData.areaContains(mouseX, mouseY))
			startScorePos.scrollOffset(ScoreData.getButtonOffset() * shift);

		// song buttons
		else
			changeIndex(shift);
		return false;
	}

	@Override
	public void enter() {
		super.enter();

		UI.enter();
		selectModsButton.resetHover();
		selectRandomButton.resetHover();
		selectMapOptionsButton.resetHover();
		selectOptionsButton.resetHover();
		hoverOffset.setTime(0);
		hoverIndex = null;
		isScrollingToFocusNode = false;
		songScrolling.released();
		songScrolling.setSpeedMultiplier(1f);
		startScorePos.setPosition(0);
		beatmapMenuTimer = -1;
		searchTransitionTimer = SEARCH_TRANSITION_TIME;
		songInfo = null;
		bgAlpha.setTime(bgAlpha.getDuration());
		songChangeTimer.setTime(songChangeTimer.getDuration());
		musicIconBounceTimer.setTime(musicIconBounceTimer.getDuration());
		starStream.clear();
		sortMenu.reset();

		// reset song stack
		randomStack = new Stack<>();

		// reload beatmaps if song folder changed
		if (songFolderChanged && stateAction != MenuState.RELOAD)
			reloadBeatmaps(false);

		// set focus node if not set (e.g. theme song playing)
		else if (focusNode == null && BeatmapSetList.get().size() > 0)
			setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);

		// reset music track
		else if (resetTrack) {
			MusicController.pause();
			MusicController.playAt(MusicController.getBeatmap().previewTime, true);
			MusicController.setPitch(1.0f);
			resetTrack = false;
		}

		// unpause track
		else if (MusicController.isPaused())
			MusicController.resume();

		// undim track
		if (MusicController.isTrackDimmed())
			MusicController.toggleTrackDimmed(1f);

		// reset game data
		if (resetGame) {
			instanceContainer.provide(Game.class).resetGameData();

			// destroy extra Clips
			MultiClip.destroyExtraClips();

			// destroy skin images, if any
			for (GameImage img : GameImage.values()) {
				if (img.isBeatmapSkinnable())
					img.destroyBeatmapSkinImage();
			}

			// reload scores
			if (focusNode != null) {
				scoreMap = ScoreDB.getMapSetScores(focusNode.getSelectedBeatmap());
				focusScores = getScoreDataForNode(focusNode, true);
			}

			// re-sort (in case play count updated)
			if (BeatmapSortOrder.current() == BeatmapSortOrder.PLAYS) {
				BeatmapSetNode oldFocusBase = BeatmapSetList.get().getBaseNode(focusNode.index);
				int oldFocusFileIndex = focusNode.beatmapIndex;
				focusNode = null;
				BeatmapSetList.get().init();
				setFocus(oldFocusBase, oldFocusFileIndex, true, true);
			}

			resetGame = false;
		}

		// state-based action
		if (stateAction != null) {
			switch (stateAction) {
			case BEATMAP:  // clear all scores
				if (stateActionNode == null || stateActionNode.beatmapIndex == -1)
					break;
				Beatmap beatmap = stateActionNode.getSelectedBeatmap();
				ScoreDB.deleteScore(beatmap);
				if (stateActionNode == focusNode) {
					focusScores = null;
					scoreMap.remove(beatmap.version);
				}
				break;
			case SCORE:  // clear single score
				if (stateActionScore == null)
					break;
				ScoreDB.deleteScore(stateActionScore);
				scoreMap = ScoreDB.getMapSetScores(focusNode.getSelectedBeatmap());
				focusScores = getScoreDataForNode(focusNode, true);
				startScorePos.setPosition(0);
				break;
			case BEATMAP_DELETE_CONFIRM:  // delete song group
				if (stateActionNode == null)
					break;
				BeatmapSetNode
					prev = BeatmapSetList.get().getBaseNode(stateActionNode.index - 1),
					next = BeatmapSetList.get().getBaseNode(stateActionNode.index + 1);
				int oldIndex = stateActionNode.index, focusNodeIndex = focusNode.index, startNodeIndex = startNode.index;
				BeatmapSetList.get().deleteSongGroup(stateActionNode);
				if (oldIndex == focusNodeIndex) {
					if (prev != null)
						setFocus(prev, -1, true, true);
					else if (next != null)
						setFocus(next, -1, true, true);
					else {
						startNode = focusNode = null;
						oldFocusNode = null;
						randomStack = new Stack<SongNode>();
						songInfo = null;
						scoreMap = null;
						focusScores = null;
					}
				} else if (oldIndex == startNodeIndex) {
					if (startNode.prev != null)
						startNode = startNode.prev;
					else if (startNode.next != null)
						startNode = startNode.next;
					else {
						startNode = null;
						songInfo = null;
					}
				}
				break;
			case BEATMAP_DELETE_SELECT:  // delete single song
				if (stateActionNode == null)
					break;
				int index = stateActionNode.index;
				BeatmapSetList.get().deleteSong(stateActionNode);
				if (stateActionNode == focusNode) {
					if (stateActionNode.prev != null &&
					    !(stateActionNode.next != null && stateActionNode.next.index == index)) {
						if (stateActionNode.prev.index == index)
							setFocus(stateActionNode.prev, 0, true, true);
						else
							setFocus(stateActionNode.prev, -1, true, true);
					} else if (stateActionNode.next != null) {
						if (stateActionNode.next.index == index)
							setFocus(stateActionNode.next, 0, true, true);
						else
							setFocus(stateActionNode.next, -1, true, true);
					}
				} else if (stateActionNode == startNode) {
					if (startNode.prev != null)
						startNode = startNode.prev;
					else if (startNode.next != null)
						startNode = startNode.next;
				}
				break;
			case RELOAD:  // reload beatmaps
				reloadBeatmaps(true);
				break;
			case BEATMAP_FAVORITE:  // removed favorite, reset beatmap list
				if (BeatmapGroup.current() == BeatmapGroup.FAVORITE) {
					startNode = focusNode = null;
					oldFocusNode = null;
					randomStack = new Stack<SongNode>();
					songInfo = null;
					scoreMap = null;
					focusScores = null;
					BeatmapSetList.get().reset();
					BeatmapSetList.get().init();
					setFocus(BeatmapSetList.get().getRandomNode(), -1, true, true);
				}
				break;
			default:
				break;
			}
			stateAction = null;
			stateActionNode = null;
			stateActionScore = null;
		}
	}

	/**
	 * Shifts the startNode forward (+) or backwards (-) by a given number of nodes.
	 * Initiates sliding "animation" by shifting the button Y position.
	 * @param shift the number of nodes to shift
	 */
	private void changeIndex(int shift) {
		if (shift == 0)
			return;

		songScrolling.scrollOffset(shift * buttonOffset);
	}

	/**
	 * Updates the song list data required for drawing.
	 */
	private void updateDrawnSongPosition() {
		float songNodePosDrawn = songScrolling.getPosition();

		int startNodeIndex = (int) (songNodePosDrawn / buttonOffset);
		buttonY = -songNodePosDrawn + buttonOffset * startNodeIndex + headerY - DIVIDER_LINE_WIDTH;

		float max = (BeatmapSetList.get().size() + (focusNode != null ? focusNode.getBeatmapSet().size() : 0));
		songScrolling.setMinMax(0 - buttonOffset * 2, (max - MAX_SONG_BUTTONS - 1 + 2) * buttonOffset);

		// negative startNodeIndex means the first Node is below the header so offset it.
		if (startNodeIndex <= 0) {
			startNodeOffset = -startNodeIndex;
			startNodeIndex = 0;
		} else {
			startNodeOffset = 0;
		}

		// Finds the start node with the expanded focus node in mind.
		if (focusNode != null && startNodeIndex >= focusNode.index) {
			// below the focus node.
			if (startNodeIndex <= focusNode.index + focusNode.getBeatmapSet().size()) {
				// inside the focus nodes expanded nodes.
				int nodeIndex = startNodeIndex - focusNode.index;
				startNode = BeatmapSetList.get().getBaseNode(focusNode.index);
				startNode = startNode.next;
				for (int i = 0; i < nodeIndex; i++)
					startNode = startNode.next;
			} else {
				startNodeIndex -= focusNode.getBeatmapSet().size() - 1;
				startNode = BeatmapSetList.get().getBaseNode(startNodeIndex);
			}
		} else
			startNode = BeatmapSetList.get().getBaseNode(startNodeIndex);
	}

	/**
	 * Sets a new focus node.
	 * @param node the base node; it will be expanded if it isn't already
	 * @param beatmapIndex the beatmap element to focus; if out of bounds, it will be randomly chosen
	 * @param changeStartNode if true, startNode will be set to the first node in the group
	 * @param preview whether to start at the preview time (true) or beginning (false)
	 * @return the old focus node
	 */
	public BeatmapSetNode setFocus(BeatmapSetNode node, int beatmapIndex, boolean changeStartNode, boolean preview) {
		if (node == null)
			return null;

		hoverOffset.setTime(0);
		hoverIndex = null;
		songInfo = null;
		songChangeTimer.setTime(0);
		musicIconBounceTimer.setTime(0);
		BeatmapSetNode oldFocus = focusNode;

		// expand node before focusing it
		int expandedIndex = BeatmapSetList.get().getExpandedIndex();
		if (node.index != expandedIndex) {
			node = BeatmapSetList.get().expand(node.index);

			// calculate difficulties
			calculateStarRatings(node.getBeatmapSet());

			// if start node was previously expanded, move it
			if (startNode != null && startNode.index == expandedIndex)
				startNode = BeatmapSetList.get().getBaseNode(startNode.index);
		}

		// check beatmapIndex bounds
		int length = node.getBeatmapSet().size();
		if (beatmapIndex < 0 || beatmapIndex > length - 1)  // set a random index
			beatmapIndex = (int) (Math.random() * length);

		focusNode = BeatmapSetList.get().getNode(node, beatmapIndex);
		Beatmap beatmap = focusNode.getSelectedBeatmap();
		if (beatmap.timingPoints == null) {
			// parse timing points so we can pulse the logo
			BeatmapParser.parseTimingPoints(beatmap);
		}
		MusicController.play(beatmap, false, preview);

		// load scores
		scoreMap = ScoreDB.getMapSetScores(beatmap);
		focusScores = getScoreDataForNode(focusNode, true);
		startScorePos.setPosition(0);

		if (oldFocus != null && oldFocus.getBeatmapSet() != node.getBeatmapSet()) {
			// close previous node
			if (node.index > oldFocus.index) {
				float offset = (oldFocus.getBeatmapSet().size() - 1) * buttonOffset;
				songScrolling.addOffset(-offset);
			}

			if (Math.abs(node.index - oldFocus.index) > MAX_SONG_BUTTONS) {
				// open from the middle
				float offset = ((node.getBeatmapSet().size() - 1) * buttonOffset) / 2f;
				songScrolling.addOffset(offset);
			} else if (node.index > oldFocus.index) {
				// open from the bottom
				float offset = (node.getBeatmapSet().size() - 1) * buttonOffset;
				songScrolling.addOffset(offset);
			} else {
				// open from the top
			}
		}

		// change the focus node
		if (changeStartNode || (startNode.index == 0 && startNode.beatmapIndex == -1 && startNode.prev == null)) {
			if (startNode == null || displayContainer.isInState(SongMenu.class)) {
				songScrolling.setPosition((node.index - 1) * buttonOffset);
			} else {
				isScrollingToFocusNode = true;
				songScrolling.setSpeedMultiplier(2f);
				songScrolling.released();
			}
		}

		updateDrawnSongPosition();

		// make sure focusNode is on the screen
		int val = focusNode.index + focusNode.beatmapIndex;
		if (val * buttonOffset <= songScrolling.getPosition())
			songScrolling.scrollToPosition(val * buttonOffset);
		else if (val * buttonOffset - (footerY - headerY - buttonOffset) >= songScrolling.getPosition())
			songScrolling.scrollToPosition(val * buttonOffset - (footerY - headerY - buttonOffset));

		/*
		// Centers selected node
		int val = focusNode.index + focusNode.beatmapIndex - MAX_SONG_BUTTONS/2;
		songScrolling.scrollToPosition(val * buttonOffset);
		//*/

		/*
		// Attempts to make all nodes in the set at least visible
		if( focusNode.index * buttonOffset < songScrolling.getPosition())
			songScrolling.scrollToPosition(focusNode.index * buttonOffset);
		if ( ( focusNode.index + focusNode.getBeatmapSet().size() ) * buttonOffset > songScrolling.getPosition() + footerY - headerY)
			songScrolling.scrollToPosition((focusNode.index + focusNode.getBeatmapSet().size() ) * buttonOffset - (footerY - headerY));
		//*/

		// load background image
		beatmap.loadBackground();
		boolean isBgNull = lastBackgroundImage == null || beatmap.bg == null;
		if ((isBgNull && lastBackgroundImage != beatmap.bg) || (!isBgNull && !beatmap.bg.equals(lastBackgroundImage))) {
			bgAlpha.setTime(0);
			lastBackgroundImage = beatmap.bg;
		}

		return oldFocus;
	}

	/**
	 * Triggers a reset of game data upon entering this state.
	 */
	public void resetGameDataOnLoad() { resetGame = true; }

	/**
	 * Triggers a reset of the music track upon entering this state.
	 */
	public void resetTrackOnLoad() { resetTrack = true; }

	/**
	 * Performs an action based on a menu state upon entering this state.
	 * @param menuState the menu state determining the action
	 */
	public void doStateActionOnLoad(MenuState menuState) { doStateActionOnLoad(menuState, null, null); }

	/**
	 * Performs an action based on a menu state upon entering this state.
	 * @param menuState the menu state determining the action
	 * @param node the song node to perform the action on
	 */
	public void doStateActionOnLoad(MenuState menuState, BeatmapSetNode node) {
		doStateActionOnLoad(menuState, node, null);
	}

	/**
	 * Performs an action based on a menu state upon entering this state.
	 * @param menuState the menu state determining the action
	 * @param scoreData the score data to perform the action on
	 */
	public void doStateActionOnLoad(MenuState menuState, ScoreData scoreData) {
		doStateActionOnLoad(menuState, null, scoreData);
	}

	/**
	 * Performs an action based on a menu state upon entering this state.
	 * @param menuState the menu state determining the action
	 * @param node the song node to perform the action on
	 * @param scoreData the score data to perform the action on
	 */
	private void doStateActionOnLoad(MenuState menuState, BeatmapSetNode node, ScoreData scoreData) {
		stateAction = menuState;
		stateActionNode = node;
		stateActionScore = scoreData;
	}

	/**
	 * Returns all the score data for an BeatmapSetNode from scoreMap.
	 * If no score data is available for the node, return null.
	 * @param node the BeatmapSetNode
	 * @param setTimeSince whether or not to set the "time since" field for the scores
	 * @return the ScoreData array
	 */
	private ScoreData[] getScoreDataForNode(BeatmapSetNode node, boolean setTimeSince) {
		if (scoreMap == null || scoreMap.isEmpty() || node.beatmapIndex == -1)  // node not expanded
			return null;

		Beatmap beatmap = node.getSelectedBeatmap();
		ScoreData[] scores = scoreMap.get(beatmap.version);
		if (scores == null || scores.length < 1)  // no scores
			return null;

		ScoreData s = scores[0];
		if (beatmap.beatmapID == s.MID && beatmap.beatmapSetID == s.MSID &&
		    beatmap.title.equals(s.title) && beatmap.artist.equals(s.artist) &&
		    beatmap.creator.equals(s.creator)) {
			if (setTimeSince) {
				for (int i = 0; i < scores.length; i++)
					scores[i].getTimeSince();
			}
			return scores;
		} else
			return null;  // incorrect map
	}

	/**
	 * Reloads all beatmaps.
	 * @param fullReload if true, also clear the beatmap cache and invoke the unpacker
	 */
	private void reloadBeatmaps(final boolean fullReload) {
		songFolderChanged = false;

		// reset state and node references
		MusicController.reset();
		startNode = focusNode = null;
		scoreMap = null;
		focusScores = null;
		oldFocusNode = null;
		randomStack = new Stack<SongNode>();
		songInfo = null;
		hoverOffset.setTime(0);
		hoverIndex = null;
		searchTextField.setText("");
		searchTimer = SEARCH_DELAY;
		searchTransitionTimer = SEARCH_TRANSITION_TIME;
		searchResultString = null;
		lastBackgroundImage = null;

		// reload songs in new thread
		reloadThread = new BeatmapReloadThread(fullReload);
		reloadThread.start();
	}

	/**
	 * Returns whether a delayed/animated event is currently blocking user input.
	 * @return true if blocking input
	 */
	private boolean isInputBlocked() {
		return (reloadThread != null || beatmapMenuTimer > -1 || isScrollingToFocusNode);
	}

	/**
	 * Returns the beatmap node at the given location.
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @return the node, or {@code null} if none
	 */
	private BeatmapSetNode getNodeAtPosition(int x, int y) {
		if (y <= headerY || y >= footerY)
			return null;

		int expandedIndex = BeatmapSetList.get().getExpandedIndex();
		BeatmapSetNode node = startNode;
		for (int i = startNodeOffset; i < MAX_SONG_BUTTONS + 1 && node != null; i++, node = node.next) {
			float cx = (node.index == expandedIndex) ? buttonX * 0.9f : buttonX;
			if ((x > cx && x < cx + buttonWidth) &&
				(y > buttonY + (i * buttonOffset) && y < buttonY + (i * buttonOffset) + buttonHeight))
				return node;
		}
		return null;
	}

	/**
	 * Calculates all star ratings for a beatmap set.
	 * @param beatmapSet the set of beatmaps
	 */
	private void calculateStarRatings(BeatmapSet beatmapSet) {
		for (Beatmap beatmap : beatmapSet) {
			if (beatmap.starRating >= 0) {  // already calculated
				beatmapsCalculated.put(beatmap, beatmapsCalculated.get(beatmap));
				continue;
			}

			// if timing points are already loaded before this (for whatever reason),
			// don't clear the array fields to be safe
			boolean hasTimingPoints = (beatmap.timingPoints != null);

			BeatmapDifficultyCalculator diffCalc = new BeatmapDifficultyCalculator(beatmap);
			diffCalc.calculate();
			if (diffCalc.getStarRating() == -1)
				continue;  // calculations failed

			// save star rating
			beatmap.starRating = diffCalc.getStarRating();
			BeatmapDB.setStars(beatmap);
			beatmapsCalculated.put(beatmap, !hasTimingPoints);
		}
	}

	/**
	 * Starts the game.
	 */
	private void startGame() {
		if (MusicController.isTrackLoading())
			return;

		Beatmap beatmap = MusicController.getBeatmap();
		if (focusNode == null || beatmap != focusNode.getSelectedBeatmap()) {
			EventBus.post(new BarNotificationEvent("Unable to load the beatmap audio."));
			return;
		}

		// turn on "auto" mod if holding "ctrl" key
		if (displayContainer.input.isKeyDown(Input.KEY_RCONTROL) || displayContainer.input.isKeyDown(Input.KEY_LCONTROL)) {
			if (!GameMod.AUTO.isActive())
				GameMod.AUTO.toggle(true);
		}

		SoundController.playSound(SoundEffect.MENUHIT);
		MultiClip.destroyExtraClips();
		Game gameState = instanceContainer.provide(Game.class);
		gameState.loadBeatmap(beatmap);
		gameState.setRestart(Game.Restart.NEW);
		gameState.setReplay(null);
		displayContainer.switchState(Game.class);
	}
}
