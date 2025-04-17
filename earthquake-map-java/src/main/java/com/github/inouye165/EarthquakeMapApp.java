package com.github.inouye165;

// ------------------------------------------------------------
// EarthquakeMapApp (JXMapViewer)
//   – Shows USGS earthquakes (M ≥ 2.5, past 7 days)
//   – Hover to see magnitude, location, and time
//   – Slider filters quakes by age (5 minutes → 7 days)
// ------------------------------------------------------------

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCenter;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.viewer.*;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EarthquakeMapApp {

    private static final float MAG_THRESHOLD_MODERATE = 5.0f;
    private static final float MAG_THRESHOLD_LIGHT = 4.0f;
    private static final Color COLOR_MINOR = new Color(70, 115, 180);
    private static final Color COLOR_LIGHT = new Color(254, 224, 139);
    private static final Color COLOR_MODERATE = new Color(215, 48, 39);
    private static final Color COLOR_BORDER = Color.DARK_GRAY;
    private static final long MAX_MINUTES = 7L * 24 * 60; // 7 days
    private static List<EarthquakeWaypoint> allQuakes = new ArrayList<>();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    static class EarthquakeWaypoint implements Waypoint {
        private final GeoPosition pos;
        private final double mag;
        private final String title;
        private final long epochMs;

        EarthquakeWaypoint(double lat, double lon, double mag, String title, long epochMs) {
            this.pos = new GeoPosition(lat, lon);
            this.mag = mag;
            this.title = title;
            this.epochMs = epochMs;
        }

        @Override
        public GeoPosition getPosition() {
            return pos;
        }

        double getMagnitude() {
            return mag;
        }

        String getTitle() {
            return title;
        }

        long getEpochMs() {
            return epochMs;
        }
    }

    static class EarthquakeRenderer implements WaypointRenderer<Waypoint> {
        @Override
        public void paintWaypoint(Graphics2D g, JXMapViewer map, Waypoint wp) {
            if (!(wp instanceof EarthquakeWaypoint eq)) return;
            int r;
            Color fill;
            if (eq.getMagnitude() >= MAG_THRESHOLD_MODERATE) {
                fill = COLOR_MODERATE;
                r = 10;
            } else if (eq.getMagnitude() >= MAG_THRESHOLD_LIGHT) {
                fill = COLOR_LIGHT;
                r = 7;
            } else {
                fill = COLOR_MINOR;
                r = 5;
            }
            Point2D p = map.getTileFactory().geoToPixel(eq.getPosition(), map.getZoom());
            Ellipse2D c = new Ellipse2D.Double(p.getX() - r, p.getY() - r, r * 2, r * 2);
            Paint po = g.getPaint();
            Stroke so = g.getStroke();
            RenderingHints ho = g.getRenderingHints();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fill(c);
            g.setColor(COLOR_BORDER);
            g.setStroke(new BasicStroke(1));
            g.draw(c);
            g.setPaint(po);
            g.setStroke(so);
            g.setRenderingHints(ho);
        }
    }

    public static void main(String[] args) {
        JXMapViewer viewer = createMapViewer();
        createDataWorker(viewer).execute();
        SwingUtilities.invokeLater(() -> createAndShowGUI(viewer));
    }

    private static JXMapViewer createMapViewer() {
        JXMapViewer map = new JXMapViewer() {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                Painter<?> overlay = getOverlayPainter();
                if (!(overlay instanceof WaypointPainter<?> wpPainter)) return null;
                Set<?> wps = wpPainter.getWaypoints();
                if (wps == null || wps.isEmpty()) return null;
                Rectangle vp = getViewportBounds();
                int maxSq = 15 * 15;
                double best = Double.MAX_VALUE;
                EarthquakeWaypoint nearest = null;
                for (Object o : wps) {
                    Waypoint w = (Waypoint) o;
                    Point2D world = getTileFactory().geoToPixel(w.getPosition(), getZoom());
                    double dx = world.getX() - vp.getX();
                    double dy = world.getY() - vp.getY();
                    double d2 = Point2D.distanceSq(dx, dy, e.getX(), e.getY());
                    if (d2 < maxSq && d2 < best && w instanceof EarthquakeWaypoint eq) {
                        best = d2;
                        nearest = eq;
                    }
                }
                if (nearest != null) {
                    String timeStr = FMT.format(Instant.ofEpochMilli(nearest.getEpochMs()));
                    return "<html><b>" + nearest.getTitle() + "</b><br>Magnitude: " +
                            String.format("%.1f", nearest.getMagnitude()) + "<br>Time: " + timeStr + "</html>";
                }
                return null;
            }
        };
        map.setTileFactory(new DefaultTileFactory(new OSMTileFactoryInfo()));
        map.setToolTipText("");
        map.setZoom(3);
        map.setAddressLocation(new GeoPosition(20, 0));
        PanMouseInputListener pan = new PanMouseInputListener(map);
        map.addMouseListener(pan);
        map.addMouseMotionListener(pan);
        map.addMouseWheelListener(new ZoomMouseWheelListenerCenter(map));
        return map;
    }

    private static SwingWorker<Set<Waypoint>, Void> createDataWorker(JXMapViewer map) {
        return new SwingWorker<>() {
            @Override
            protected Set<Waypoint> doInBackground() throws Exception {
                String json = fetchQuakeData();
                Set<EarthquakeWaypoint> parsed = parseQuakeData(json);
                allQuakes = new ArrayList<>(parsed);
                return new HashSet<>(parsed);
            }

            @Override
            protected void done() {
                try {
                    applyWaypoints(map, get());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
    }

    private static void applyWaypoints(JXMapViewer map, Set<Waypoint> wps) {
        WaypointPainter<Waypoint> painter = new WaypointPainter<>();
        painter.setWaypoints(wps);
        painter.setRenderer(new EarthquakeRenderer());
        map.setOverlayPainter(painter);
        map.repaint();
    }

    private static void createAndShowGUI(JXMapViewer map) {
        JFrame f = new JFrame("Earthquake Map (JXMapViewer)");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setLayout(new BorderLayout());
        f.add(map, BorderLayout.CENTER);
        f.add(createLegendPanel(), BorderLayout.WEST);

        JSlider slider = new JSlider(JSlider.HORIZONTAL, 5, (int) MAX_MINUTES, (int) MAX_MINUTES);
        slider.setBorder(BorderFactory.createTitledBorder("Show events from last (minutes)"));
        slider.setMajorTickSpacing(1440);
        slider.setMinorTickSpacing(60);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                int mins = slider.getValue();
                long cutoff = System.currentTimeMillis() - mins * 60L * 1000L;
                Set<Waypoint> filtered = new HashSet<>();
                for (EarthquakeWaypoint eq : allQuakes)
                    if (eq.getEpochMs() >= cutoff)
                        filtered.add(eq);
                applyWaypoints(map, filtered);
            }
        });

        f.add(slider, BorderLayout.SOUTH);
        f.setSize(1000, 750);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static String fetchQuakeData() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson"))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200) return res.body();
        throw new RuntimeException("HTTP " + res.statusCode());
    }

    @SuppressWarnings("unchecked")
    private static Set<EarthquakeWaypoint> parseQuakeData(String json) throws Exception {
        Set<EarthquakeWaypoint> out = new HashSet<>();
        Map<String, Object> root = new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> features = (List<Map<String, Object>>) root.get("features");
        if (features == null) return out;
        for (Map<String, Object> ft : features) {
            try {
                Map<String, Object> props = (Map<String, Object>) ft.get("properties");
                Map<String, Object> geom = (Map<String, Object>) ft.get("geometry");
                if (props == null || geom == null || !"Point".equals(geom.get("type"))) continue;
                Double mag = parseDouble(props.get("mag"));
                Long time = parseLong(props.get("time"));
                String title = props.get("place") != null ? props.get("place").toString() : "N/A";
                List<?> coords = (List<?>) geom.get("coordinates");
                if (mag == null || time == null || coords == null || coords.size() < 2) continue;
                double lon = ((Number) coords.get(0)).doubleValue();
                double lat = ((Number) coords.get(1)).doubleValue();
                out.add(new EarthquakeWaypoint(lat, lon, mag, title, time));
            } catch (Exception ignore) {}
        }
        return out;
    }

    private static Double parseDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) try { return Double.parseDouble(o.toString()); } catch (NumberFormatException ignored) {}
        return null;
    }

    private static Long parseLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) try { return Long.parseLong(o.toString()); } catch (NumberFormatException ignored) {}
        return null;
    }

    private static JPanel createLegendPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 10, 10, 10),
                BorderFactory.createTitledBorder("Legend")));
        p.setBackground(new Color(230, 230, 230, 200));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 5);
        addLegendRow(p, gbc, COLOR_MODERATE, 16, "5.0+ Magnitude");
        addLegendRow(p, gbc, COLOR_LIGHT, 12, "4.0–4.9 Magnitude");
        addLegendRow(p, gbc, COLOR_MINOR, 8, "< 4.0 Magnitude");
        gbc.weighty = 1;
        p.add(Box.createVerticalGlue(), gbc);
        return p;
    }

    private static void addLegendRow(JPanel p, GridBagConstraints gbc, Color c, int d, String label) {
        p.add(new JLabel(coloredCircleIcon(c, d)), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        p.add(new JLabel(label), gbc);
        gbc.gridx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
    }

    private static Icon coloredCircleIcon(Color c, int d) {
        BufferedImage img = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fillOval(0, 0, d, d);
        g.setColor(COLOR_BORDER);
        g.setStroke(new BasicStroke(1));
        g.drawOval(0, 0, d - 1, d - 1);
        g.dispose();
        return new ImageIcon(img);
    }
}
