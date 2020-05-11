package com.steven10172.corptracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class CorpEventTrackerPanel extends PluginPanel {
    private static final String HTML_LABEL_TEMPLATE =
            "<html><body style='color:%s'>%s<span style='color:white'>%s</span></body></html>";
    private String searchString = null;

    private final JPanel killListView = new JPanel(new GridBagLayout());
    private final JPanel display = new JPanel();
    private final JPanel actionsContainer = new JPanel();
    private final JPanel overallPanel = new JPanel(new BorderLayout());
    private final JLabel overallKillsLabel = new JLabel();
    private final JLabel overallGpLabel = new JLabel();
    private final JLabel overallIcon = new JLabel();
    private final JPanel searchPanel = new JPanel(new BorderLayout());
    private final IconTextField searchBar = new IconTextField();

    final HashMap<UUID, BossKillEvent> bossKills = new HashMap<>();

    private final CorpEventTrackerPlugin plugin;
    private final CorpEventTrackerConfig config;

    CorpEventTrackerPanel(final CorpEventTrackerPlugin plugin, final CorpEventTrackerConfig config, ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.config = config;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        // Header - actions container
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(5, 0, 0, 0));

        // Overall - Information about all kills
        overallPanel.setBorder(new EmptyBorder(5, 10, 5, 0));
        overallPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Add icon and contents
        final JPanel overallInfo = new JPanel();
        overallInfo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        overallInfo.setLayout(new GridLayout(3, 1));
        overallInfo.setBorder(new EmptyBorder(2, 0, 2, 0));
        overallKillsLabel.setFont(FontManager.getRunescapeSmallFont());
        overallGpLabel.setFont(FontManager.getRunescapeSmallFont());
        overallInfo.add(overallKillsLabel);
        overallInfo.add(overallGpLabel);
        overallPanel.add(overallIcon, BorderLayout.WEST);
        overallPanel.add(overallInfo, BorderLayout.CENTER);

        final JMenuItem deleteKill = new JMenuItem("Delete All Kills");
        deleteKill.addActionListener(e -> {
            this.removeAllKills();
        });

        // Create right click delete menu
        final JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.setBorder(new EmptyBorder(5, 5, 5, 5));
        popupMenu.add(deleteKill);
        overallPanel.setComponentPopupMenu(popupMenu);

        // Display - Kills Container
        display.setLayout(new BorderLayout());

        // Search bar
        searchBar.setIcon(IconTextField.Icon.SEARCH);
        searchBar.setPreferredSize(new Dimension(100, 30));
        searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchBar.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
        searchBar.addActionListener(e -> executor.execute(this::executeSearch));
        searchBar.addClearListener(e -> clearSearch());

        display.add(killListView);
        header.add(actionsContainer, BorderLayout.NORTH);

        searchPanel.setBorder(new EmptyBorder(0, 0, 5, 0));
        searchPanel.add(searchBar);

        add(header, BorderLayout.NORTH);
        add(searchPanel, BorderLayout.NORTH);
        add(overallPanel, BorderLayout.NORTH);
        add(display, BorderLayout.CENTER);
    }

    void loadHeaderIcon(BufferedImage img) {
        overallIcon.setIcon(new ImageIcon(img));
    }

    public void addRecord(BossKillEvent bossKill) {
        this.updateRecord(bossKill);
    }

    public void updateRecord(BossKillEvent bossKill) {
        this.bossKills.remove(bossKill.getUuid());

        this.bossKills.put(bossKill.getUuid(), bossKill);
        rebuild();
    }

    public BossKillEvent getKill(UUID id) {
        return bossKills.get(id);
    }

    public void rebuild() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.gridx = 0;
        constraints.gridy = 0;

        killListView.removeAll();

        bossKills.entrySet()
                .stream()
                .sorted((k1, k2) -> k2.getValue().getTime().compareTo(k1.getValue().getTime()))
                .forEach(action -> {
                    UUID id = action.getKey();
                    BossKillEvent bossKillEvent = action.getValue();

                    if (bossKillEvent.search(this.searchString)) {
                        KillTrackerBox kill = new KillTrackerBox(id, bossKillEvent, this, this.searchString);
                        killListView.add(kill, constraints);
                        constraints.gridy++;
                    }
                });

        updateOverall();
        repaint();
        revalidate();
    }

    public void removeKill(UUID id) {
        log.info("Delete Kill" + id.toString());
        this.bossKills.remove(id);
        rebuild();
    }

    private void removeAllKills() {
        log.info("Delete ALl Kills");
        this.bossKills.clear();
        rebuild();
    }

    public void removePlayerFromKill(UUID id, String participant) {
        log.info(String.format("Removing player: %s from %s", participant, id.toString()));
        this.bossKills.get(id).getParticipants().remove(participant);
        rebuild();
    }

    private void updateOverall() {
        long overallKills = 0;
        long overallGe = 0;

        for (Map.Entry<UUID, BossKillEvent> entry : bossKills.entrySet()) {
            BossKillEvent kill = entry.getValue();
            if (kill.search(this.searchString)) {
                overallGe += kill.getItem().getTotalGePrice();
                overallKills++;
            }
        }

        overallKillsLabel.setText(htmlLabel("Total count: ", overallKills));
        overallGpLabel.setText(htmlLabel("Total value: ", overallGe));
    }

    private static String htmlLabel(String key, long value) {
        final String valueStr = QuantityFormatter.quantityToStackSize(value);
        return String.format(HTML_LABEL_TEMPLATE, ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR), key, valueStr);
    }

    public void setFilter(String filterStr) {
        this.searchBar.setText(filterStr);
        executeSearch();
    }

    private void executeSearch() {
        this.searchString = this.searchBar.getText();
        log.info("Search: " + searchString);
        SwingUtilities.invokeLater(this::rebuild);
    }

    private void clearSearch() {
        this.searchString = null;
        log.info("Clear search");
        SwingUtilities.invokeLater(this::rebuild);
    }
}
