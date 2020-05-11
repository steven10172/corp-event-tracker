package com.steven10172.corptracker;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public class KillTrackerBox extends JPanel {
    private static final int TITLE_PADDING = 5;

    private final JLabel subTitleLabel = new JLabel();
    private final JPanel logTitle = new JPanel();
    private final JLabel priceLabel = new JLabel();
    private final JPanel killList = new JPanel();
    private final BossKillEvent bossKillEvent;
    private final UUID id;
    private final CorpEventTrackerPanel panel;
    private final String searchString;

    KillTrackerBox(final UUID id, final BossKillEvent bossKillEvent, final CorpEventTrackerPanel panel, final String searchString) {
        this.id = id;
        this.bossKillEvent = bossKillEvent;
        this.panel = panel;
        this.searchString = searchString;

        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(5, 0, 5, 0));

        logTitle.setLayout(new BoxLayout(logTitle, BoxLayout.X_AXIS));
        logTitle.setBorder(new EmptyBorder(7, 7, 7, 7));
        logTitle.setBackground(this.bossKillEvent.isInProgress() ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR.darker());
        final String killTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(bossKillEvent.getTime());
        logTitle.setToolTipText(killTime);

        JLabel titleLabel = new JLabel();
        titleLabel.setText(bossKillEvent.getKillOwner()); // Who got the kill
        titleLabel.setFont(FontManager.getRunescapeSmallFont());
        titleLabel.setForeground(Color.WHITE);

        logTitle.add(titleLabel);

        subTitleLabel.setFont(FontManager.getRunescapeSmallFont());
        subTitleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

        String itemName = bossKillEvent.getItem().getName();
        String fullDropName = (bossKillEvent.getItem().getQuantity() == 1 ? itemName : String.format("%d x %s", bossKillEvent.getItem().getQuantity(), bossKillEvent.getItem().getName()));
        subTitleLabel.setText(this.bossKillEvent.isInProgress() ? "" : fullDropName); // Item Name
        subTitleLabel.setMinimumSize(new Dimension(1, subTitleLabel.getPreferredSize().height));

        logTitle.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));
        logTitle.add(subTitleLabel);
        logTitle.add(Box.createHorizontalGlue());
        logTitle.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));

        priceLabel.setFont(FontManager.getRunescapeSmallFont());
        priceLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        priceLabel.setText("");
        if (!this.bossKillEvent.isInProgress()) {
            priceLabel.setText(QuantityFormatter.quantityToStackSize(bossKillEvent.getItem().getTotalGePrice()) + " gp"); // Short value
            priceLabel.setToolTipText(QuantityFormatter.formatNumber(bossKillEvent.getItem().getTotalGePrice()) + " gp"); // Full value
        }
        logTitle.add(priceLabel);

        killList.setLayout(new BorderLayout());

        buildKillList();

        // Collapse the player list when someone clicks the title
        logTitle.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    if (isCollapsed()) {
                        expand();
                    } else {
                        collapse();
                    }
                }
            }
        });

        final JMenuItem deleteKill = new JMenuItem("Delete Kill");
        deleteKill.addActionListener(e -> {
            this.panel.removeKill(id);
        });

        // Create right click delete menu
        final JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.setBorder(new EmptyBorder(5, 5, 5, 5));
        popupMenu.add(deleteKill);
        logTitle.setComponentPopupMenu(popupMenu);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.gridx = 0;
        constraints.gridy = 0;

        add(logTitle, constraints);

        constraints.gridy++;
        add(killList, constraints);
    }

    private void buildKillList() {
        boolean hasItemMatch = false;
        boolean hasOwnerMatch = false;

        if (this.searchString != null) {
            hasItemMatch = this.bossKillEvent.getItem().getName().toLowerCase().contains(this.searchString);
            hasOwnerMatch = this.bossKillEvent.getKillOwner().toLowerCase().contains(this.searchString);
        }

        boolean hasMatch = hasItemMatch || hasOwnerMatch;

        List<String> filteredParticipants = this.bossKillEvent
                .getParticipants()
                .stream()
                .sorted()
                .filter(p -> this.searchString == null || hasMatch || p.toLowerCase().contains(this.searchString))
                .collect(Collectors.toList());

        killList.removeAll();

        final JPanel killContainer = new JPanel();
        int participantCount = filteredParticipants.size();

        if (participantCount > 0) {
            int rowCount = ((participantCount % 2 == 0) ? 0 : 1) + participantCount / 2;
            killContainer.setLayout(new GridLayout(rowCount, 2, 1, 1));

            filteredParticipants.forEach(participant -> {
                final JLabel playerLabel = new JLabel();
                playerLabel.setText(participant);
                playerLabel.setFont(FontManager.getRunescapeSmallFont());
                playerLabel.setForeground(Color.WHITE);
                playerLabel.setBorder(new EmptyBorder(2, 7, 2, 0));

                // Add menu to allow removing of a player
                final JMenuItem removePlayer = new JMenuItem("Remove Player");
                removePlayer.addActionListener(e -> {
                    this.panel.removePlayerFromKill(id, participant);
                });

                final JMenuItem filterPlayer = new JMenuItem("Filter on Player");
                filterPlayer.addActionListener(e -> {
                    this.panel.setFilter(participant);
                });

                // Create right click delete menu
                final JPopupMenu popupMenu = new JPopupMenu();
                popupMenu.setBorder(new EmptyBorder(5, 5, 5, 5));
                popupMenu.add(removePlayer);
                popupMenu.add(filterPlayer);
                playerLabel.setComponentPopupMenu(popupMenu);

                killContainer.add(playerLabel);
            });
        } else {
            killContainer.setLayout(new GridLayout(1, 1, 1, 1));
            final JLabel noPlayerLabel = new JLabel();
            noPlayerLabel.setText("@@  No Participants Identified  @@");
            noPlayerLabel.setFont(FontManager.getRunescapeFont());
            noPlayerLabel.setForeground(Color.WHITE);
            noPlayerLabel.setBorder(new EmptyBorder(2, 10, 2, 0));
            killContainer.add(noPlayerLabel);
        }

        killList.add(killContainer);
        killList.repaint();
    }

    void collapse() {
        log.info("Collapsing");
        if (!isCollapsed())
        {
            killList.setVisible(false);
            applyDimmer(false, logTitle);
        }
    }

    void expand() {
        log.info("Expanding");
        if (isCollapsed())
        {
            killList.setVisible(true);
            applyDimmer(true, logTitle);
        }
    }

    boolean isCollapsed() {
        return !killList.isVisible();
    }

    private void applyDimmer(boolean brighten, JPanel panel) {
        for (Component component : panel.getComponents()) {
            Color color = component.getForeground();

            component.setForeground(brighten ? color.brighter() : color.darker());
        }
    }
}
