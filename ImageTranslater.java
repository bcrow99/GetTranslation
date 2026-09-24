import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * GUI front end for getTranslatedImage.java -- sets dx/dy (and, as a bonus,
 * a preview smoothing pass) from sliders instead of retyping command-line
 * arguments every time.
 *
 * File / View / Parameters menus:
 *
 *   File
 *     Open   -- file chooser, spawns an independent new ImageTranslater
 *               window for the chosen file (same multi-window pattern as
 *               DeltaWriter.java: each window tracks itself in
 *               openWindowCount, and the JVM only exits once the last one
 *               closes).
 *     Save   -- writes the same two ref/shift crop files getTranslatedImage
 *               writes, with the same naming convention, same directory,
 *               same crop math -- see saveCrops() below, adapted directly
 *               from getTranslatedImage.java. Uses the CURRENT dx/dy (from
 *               the Shift sliders) and the CURRENTLY DISPLAYED pixels
 *               (original, or smoothed if the Smooth sliders are up) as the
 *               source to crop. Smoothing level is NOT recorded in the
 *               output filenames -- only dx/dy are, exactly as
 *               getTranslatedImage.java already did.
 *
 *   View
 *     Zoom In / Zoom Out / Fit / 100%, plus Ctrl+scroll-wheel zoom-at-mouse
 *     and JScrollPane scrolling -- all adapted directly from DeltaWriter.java
 *     (zoomBy(), updateDisplayImage(), the AffineTransformOp bilinear scaling
 *     approach, and the same ZOOM_FACTOR/ZOOM_MIN/ZOOM_MAX). The image is
 *     initially displayed scaled down to fit the window (fit_scale), same as
 *     DeltaWriter's own initial display.
 *
 *   Parameters
 *     Shift  -- a panel with an x slider and a y slider, each ranging from
 *               -dimension/2 to +dimension/2 (x scaled to image width, y to
 *               image height), default 0. These sliders do NOT touch the
 *               displayed image at all -- they just set the dx/dy globals
 *               Save uses. (Explicitly requested: no live shift preview.)
 *     Smooth -- a panel with two sliders, 0-10, default 0, working exactly
 *               like DeltaWriter's Smooth/Smooth2 sliders: smooth_level
 *               drives TranslateMapper.bilateralSmooth(), smooth2_level
 *               drives TranslateMapper.anisotropicSmooth(), both applied
 *               per-channel starting fresh from the pristine loaded channel
 *               data every time (never chained onto a previously-smoothed
 *               result), and the displayed image updates immediately on
 *               every slider change -- same pattern as DeltaWriter's
 *               ApplyHandler.
 *
 * Pixel extraction deliberately does NOT reuse DeltaWriter's
 * PixelGrabber-on-TYPE_3BYTE_BGR approach, which fails loudly (no window at
 * all) on any image ImageIO decodes into a different raster type -- a real
 * limitation for a general-purpose "open whatever image I point it at" tool.
 * Instead, like getTranslatedImage.java's crop()/writeImage(), this just
 * uses BufferedImage.getRGB()/setRGB() on packed ints, which works
 * regardless of the source image's internal raster layout.
 */
public class ImageTranslater
{
    // ---- Image state ---------------------------------------------------
    BufferedImage original_image;   // exactly as loaded, never modified
    BufferedImage working_image;    // TYPE_INT_RGB, rebuilt from the pristine
                                     // channels whenever the Smooth sliders
                                     // change (or holds the untouched pixels
                                     // when both are 0); this is what's
                                     // displayed AND what Save crops from
    BufferedImage display_image;    // working_image, zoom-scaled for painting
    ImageCanvas   image_canvas;
    JScrollPane   scroll_pane;
    JFrame        frame;
    String        filename;
    int           image_xdim, image_ydim;
    int           screen_xdim, screen_ydim;

    // Pristine single-channel arrays, split once at load time and never
    // written to again -- every Smooth recompute starts fresh from these,
    // exactly mirroring DeltaWriter.ApplyHandler's
    // "int[] ch = (int[]) channel_list.get(i); if (smooth_level > 0) ch = ..."
    // pattern (read fresh from the untouched source, not chained).
    int[] red_channel, green_channel, blue_channel;

    // ---- Parameters ------------------------------------------------------
    int dx = 0, dy = 0;                 // Shift -- read only at Save time
    int smooth_level = 0, smooth2_level = 0; // Smooth -- live-applied below
    JSlider shift_x_slider, shift_y_slider, smooth_slider, smooth2_slider;

    double zoom_scale = 1.0;
    double fit_scale  = 1.0;
    static final double ZOOM_FACTOR = 1.25;
    static final double ZOOM_MIN    = 0.05;
    static final double ZOOM_MAX    = 32.0;

    // Same HiDPI-font-scale fallback as DeltaWriter.java -- see its own
    // comment block below for why this exists (Linux/X11 specifically).
    static double hidpi_scale = 1.0;

    static int openWindowCount  = 0;
    static int nextWindowOffset = 0;

    public static void main(String[] args)
    {
        applyHiDpiFontScaleIfNeeded();
        if (args.length == 1)
        {
            new ImageTranslater(args[0]);
        }
        else
        {
            FileDialog fd = new FileDialog((Frame) null, "Open Image", FileDialog.LOAD);
            fd.setVisible(true);
            if (fd.getFile() != null) new ImageTranslater(new File(fd.getDirectory(), fd.getFile()).getPath());
            else System.exit(0);
        }
    }

    // =========================================================================
    // HiDPI font-scale fallback -- copied from DeltaWriter.java essentially
    // verbatim (same Linux/X11 detection problem applies here: a dense
    // menu/slider UI like this one needs it just as much as DeltaWriter does).
    // See DeltaWriter.java's own comment block for the full rationale.
    // =========================================================================

    private static double detectMissingUiScale()
    {
        try
        {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
            double current_scale = gc.getDefaultTransform().getScaleX();

            if (current_scale > 1.01) return 1.0;

            String gdk_scale_str = System.getenv("GDK_SCALE");
            if (gdk_scale_str != null)
            {
                try
                {
                    double gdk_scale = Double.parseDouble(gdk_scale_str.trim());
                    if (gdk_scale >= 1.25) return gdk_scale;
                }
                catch (NumberFormatException nfe) { /* fall through to next signal */ }
            }

            Object xft_dpi_prop = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI");
            if (xft_dpi_prop instanceof Integer)
            {
                double xft_dpi           = ((Integer) xft_dpi_prop) / 1024.0;
                double xft_implied_scale = xft_dpi / 96.0;
                if (xft_implied_scale >= 1.25) return xft_implied_scale;
            }

            int    dpi           = Toolkit.getDefaultToolkit().getScreenResolution();
            double implied_scale = dpi / 96.0;
            if (implied_scale >= 1.25) return implied_scale;

            return 1.0;
        }
        catch (Exception e)
        {
            return 1.0;
        }
    }

    private static void applyHiDpiFontScaleIfNeeded()
    {
        double scale = detectMissingUiScale();
        hidpi_scale  = scale;
        if (scale <= 1.01) return;

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        for (Object key : new java.util.Vector<Object>(defaults.keySet()))
        {
            Object value = defaults.get(key);
            if (value instanceof Font)
            {
                Font  font     = (Font) value;
                float new_size = (float) (font.getSize() * scale);
                Font  scaled   = font.deriveFont(new_size);
                defaults.put(key, scaled);
                UIManager.put(key, scaled);
            }
        }
    }

    // =========================================================================
    // Construction / loading
    // =========================================================================

    public ImageTranslater(String _filename)
    {
        filename = _filename;
        try
        {
            File file = new File(filename);
            original_image = ImageIO.read(file);
            if (original_image == null)
            {
                System.out.println("\"" + filename + "\" was not recognized as an image (unsupported format?). No window was created.");
                return;
            }
            image_xdim = original_image.getWidth();
            image_ydim = original_image.getHeight();

            extractChannels(original_image);

            working_image = new BufferedImage(image_xdim, image_ydim, BufferedImage.TYPE_INT_RGB);
            repackWorkingImage(); // smooth_level/smooth2_level are both 0 here, so this just
                                   // repacks the pristine channels; display_image isn't set
                                   // up until updateDisplayImage() below, once image_canvas
                                   // actually exists (rebuildWorkingImage() -- used by the
                                   // Smooth sliders later -- can't be used here for that
                                   // reason, since it skips the display update when
                                   // image_canvas is still null).

            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            screen_xdim = (int) screen.getWidth();
            screen_ydim = (int) screen.getHeight();
            int mw = (int) (screen_xdim * 0.70) - (int) (40 * hidpi_scale);
            int mh = (int) (screen_ydim * 0.70) - (int) (80 * hidpi_scale);
            fit_scale  = Math.min(hidpi_scale, Math.min((double) mw / image_xdim, (double) mh / image_ydim));
            zoom_scale = fit_scale;

            image_canvas = new ImageCanvas();
            scroll_pane  = new JScrollPane(image_canvas, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll_pane.getVerticalScrollBar().setUnitIncrement(16);
            scroll_pane.getHorizontalScrollBar().setUnitIncrement(16);
            scroll_pane.addMouseWheelListener(e ->
            {
                if (e.isControlDown())
                {
                    JViewport vp = scroll_pane.getViewport();
                    Point vpos = vp.getViewPosition();
                    Point mpt  = e.getPoint();
                    int mcx = mpt.x + vpos.x, mcy = mpt.y + vpos.y;
                    double old = zoom_scale;
                    zoom_scale = (e.getWheelRotation() < 0) ? Math.min(ZOOM_MAX, zoom_scale * ZOOM_FACTOR)
                                                             : Math.max(ZOOM_MIN, zoom_scale / ZOOM_FACTOR);
                    if (zoom_scale == old) return;
                    updateDisplayImage();
                    image_canvas.setPreferredSize(new Dimension((int) (image_xdim * zoom_scale), (int) (image_ydim * zoom_scale)));
                    image_canvas.revalidate();
                    image_canvas.repaint();
                    double r = zoom_scale / old;
                    vp.setViewPosition(new Point(Math.max(0, (int) (mcx * r) - mpt.x), Math.max(0, (int) (mcy * r) - mpt.y)));
                    updateTitle();
                }
                else scroll_pane.dispatchEvent(e);
            });

            frame = new JFrame("Image Translater  " + filename);
            openWindowCount++;
            frame.addWindowListener(new WindowAdapter()
            {
                public void windowClosing(WindowEvent e)
                {
                    frame.dispose();
                    if (--openWindowCount == 0) System.exit(0);
                }
            });
            frame.getContentPane().add(scroll_pane, BorderLayout.CENTER);
            frame.setJMenuBar(buildMenuBar());

            updateDisplayImage();
            image_canvas.setPreferredSize(new Dimension((int) (image_xdim * zoom_scale), (int) (image_ydim * zoom_scale)));
            updateTitle();
            frame.setSize(Math.min((int) (image_xdim * fit_scale) + (int) (40 * hidpi_scale), (int) (screen_xdim * 0.70)),
                          Math.min((int) (image_ydim * fit_scale) + (int) (80 * hidpi_scale), (int) (screen_ydim * 0.70)));
            int _off = nextWindowOffset;
            nextWindowOffset = (_off + 30) % 270;
            frame.setLocation((screen_xdim - frame.getWidth()) / 2 + _off, (screen_ydim - frame.getHeight()) / 2 + _off);
            frame.setVisible(true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Splits the loaded image into pristine red/green/blue int[] arrays via
    // getRGB() -- standard TYPE_INT_ARGB packing (0xAARRGGBB), independent of
    // whatever raster type the source image actually decoded to.
    private void extractChannels(BufferedImage img)
    {
        red_channel   = new int[image_xdim * image_ydim];
        green_channel = new int[image_xdim * image_ydim];
        blue_channel  = new int[image_xdim * image_ydim];
        int k = 0;
        for (int j = 0; j < image_ydim; j++)
        {
            for (int i = 0; i < image_xdim; i++)
            {
                int rgb = img.getRGB(i, j);
                red_channel[k]   = (rgb >> 16) & 0xff;
                green_channel[k] = (rgb >> 8)  & 0xff;
                blue_channel[k]  = rgb & 0xff;
                k++;
            }
        }
    }

    // =========================================================================
    // Menu bar
    // =========================================================================

    private JMenuBar buildMenuBar()
    {
        JMenuBar menu_bar = new JMenuBar();

        // ---- File ----
        JMenu file_menu = new JMenu("File");
        JMenuItem open_item = new JMenuItem("Open");
        open_item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        open_item.addActionListener(e ->
        {
            FileDialog fd = new FileDialog(frame, "Open Image", FileDialog.LOAD);
            fd.setVisible(true);
            if (fd.getFile() != null) new ImageTranslater(new File(fd.getDirectory(), fd.getFile()).getPath());
        });
        file_menu.add(open_item);

        JMenuItem save_item = new JMenuItem("Save");
        save_item.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        save_item.addActionListener(e -> saveCrops());
        file_menu.add(save_item);

        // ---- View ----
        JMenu view_menu = new JMenu("View");
        JMenuItem zi = new JMenuItem("Zoom In");
        zi.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK));
        zi.addActionListener(e -> zoomBy(ZOOM_FACTOR));
        view_menu.add(zi);

        JMenuItem zo = new JMenuItem("Zoom Out");
        zo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
        zo.addActionListener(e -> zoomBy(1.0 / ZOOM_FACTOR));
        view_menu.add(zo);

        JMenuItem zf = new JMenuItem("Fit");
        zf.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
        zf.addActionListener(e ->
        {
            Dimension vps = scroll_pane.getViewport().getSize();
            zoom_scale = Math.min((double) vps.width / image_xdim, (double) vps.height / image_ydim);
            updateDisplayImage();
            image_canvas.setPreferredSize(new Dimension((int) (image_xdim * zoom_scale), (int) (image_ydim * zoom_scale)));
            image_canvas.revalidate();
            image_canvas.repaint();
            updateTitle();
        });
        view_menu.add(zf);

        JMenuItem za = new JMenuItem("100%");
        za.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK));
        za.addActionListener(e ->
        {
            zoom_scale = hidpi_scale;
            updateDisplayImage();
            image_canvas.setPreferredSize(new Dimension((int) (image_xdim * zoom_scale), (int) (image_ydim * zoom_scale)));
            image_canvas.revalidate();
            image_canvas.repaint();
            updateTitle();
        });
        view_menu.add(za);

        // ---- Parameters ----
        JMenu params_menu = new JMenu("Parameters");
        params_menu.add(makeShiftDialog());
        params_menu.add(makeSmoothDialog());

        menu_bar.add(file_menu);
        menu_bar.add(view_menu);
        menu_bar.add(params_menu);
        return menu_bar;
    }

    // =========================================================================
    // Parameters > Shift -- x/y controls that only ever set dx/dy. No
    // repaint, no recompute -- explicitly NOT a live preview, per request
    // ("Shift panel just modifies global variables that determine how files
    // get saved"). Range is [-dim/2, +dim/2] per axis, default 0, as
    // specified.
    //
    // These went through two earlier designs: a plain slider (fast to
    // traverse the whole range, but fiddly to land on one specific
    // intermediate value by dragging), then a DeltaWriter-pixel_pyramid-style
    // "<"/">" stepper (picks an exact value easily, but only one step at a
    // time -- slow to cross a wide range). This combines both: the slider is
    // still here for fast coarse positioning, with "<"/">" buttons flanking
    // it that nudge it by exactly 1 step for fine adjustment -- see
    // makeShiftAxisRow() below. The buttons just call the slider's own
    // setValue() (which self-clamps to the slider's min/max), so they share
    // the slider's existing ChangeListener rather than needing separate
    // update logic -- one code path updates dx/dy and the value field
    // however the slider got moved.
    // =========================================================================

    private JMenuItem makeShiftDialog()
    {
        JMenuItem item = new JMenuItem("Shift");
        JDialog dialog = new JDialog(frame, "Shift");

        int x_lo = -image_xdim / 2, x_hi = image_xdim / 2;
        int y_lo = -image_ydim / 2, y_hi = image_ydim / 2;

        JPanel x_row = makeShiftAxisRow("x:", x_lo, x_hi, v -> dx = v);
        JPanel y_row = makeShiftAxisRow("y:", y_lo, y_hi, v -> dy = v);

        JPanel panel = new JPanel(new GridLayout(2, 1, 4, 8));
        panel.add(x_row);
        panel.add(y_row);
        dialog.add(panel);

        item.addActionListener(e ->
        {
            Point loc = frame.getLocation();
            dialog.setLocation((int) loc.getX(), (int) loc.getY() - 60);
            dialog.pack();
            dialog.setVisible(true);
        });
        return item;
    }

    // One Shift axis: label, "<" button, slider, ">" button, value field.
    // Dragging the slider jumps anywhere in [lo, hi] quickly; the "<"/">"
    // buttons step it by exactly 1 via slider.setValue(value +/- 1) for
    // landing on one precise value once you're close. Both paths run
    // through the slider's own ChangeListener below, so dx/dy and the field
    // stay in sync regardless of which one moved it. shift_x_slider /
    // shift_y_slider are stashed on the two known axis labels so callers
    // (none today, but mirrors DeltaWriter keeping its sliders as fields)
    // could reach them later.
    private JPanel makeShiftAxisRow(String label, int lo, int hi, java.util.function.IntConsumer onChange)
    {
        JSlider slider = new JSlider(lo, hi, 0);
        if (label.startsWith("x")) shift_x_slider = slider; else shift_y_slider = slider;

        JTextField field = new JTextField(5);
        field.setText(" 0 ");
        field.setEditable(false);
        field.setHorizontalAlignment(JTextField.CENTER);

        slider.addChangeListener(e ->
        {
            int v = slider.getValue();
            field.setText(" " + v + " ");
            onChange.accept(v);
        });

        JButton minusButton = new JButton("<");
        JButton plusButton  = new JButton(">");
        minusButton.addActionListener(e -> slider.setValue(slider.getValue() - 1));
        plusButton.addActionListener(e -> slider.setValue(slider.getValue() + 1));

        JPanel slider_row = new JPanel(new BorderLayout(2, 0));
        slider_row.add(minusButton, BorderLayout.WEST);
        slider_row.add(slider, BorderLayout.CENTER);
        slider_row.add(plusButton, BorderLayout.EAST);

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(slider_row, BorderLayout.CENTER);
        row.add(field, BorderLayout.EAST);
        return row;
    }

    // =========================================================================
    // Parameters > Smooth -- two sliders (0-10, default 0), same range and
    // live-apply behavior as DeltaWriter's Smooth/Smooth2. Every change
    // recomputes working_image from scratch off the pristine channels (see
    // rebuildWorkingImage()) and immediately redisplays.
    // =========================================================================

    private JMenuItem makeSmoothDialog()
    {
        JMenuItem item = new JMenuItem("Smooth");
        JDialog dialog = new JDialog(frame, "Smooth");

        smooth_slider  = new JSlider(0, 10, 0);
        smooth2_slider = new JSlider(0, 10, 0);
        JTextField f1 = new JTextField(3);
        JTextField f2 = new JTextField(3);
        f1.setText(" 0 ");
        f2.setText(" 0 ");

        smooth_slider.addChangeListener(e ->
        {
            smooth_level = smooth_slider.getValue();
            f1.setText(" " + smooth_level + " ");
            rebuildWorkingImage();
        });
        smooth2_slider.addChangeListener(e ->
        {
            smooth2_level = smooth2_slider.getValue();
            f2.setText(" " + smooth2_level + " ");
            rebuildWorkingImage();
        });

        JPanel row1 = new JPanel(new BorderLayout());
        row1.add(new JLabel("Smooth: "), BorderLayout.WEST);
        row1.add(smooth_slider, BorderLayout.CENTER);
        row1.add(f1, BorderLayout.EAST);

        JPanel row2 = new JPanel(new BorderLayout());
        row2.add(new JLabel("Smooth2: "), BorderLayout.WEST);
        row2.add(smooth2_slider, BorderLayout.CENTER);
        row2.add(f2, BorderLayout.EAST);

        JPanel panel = new JPanel(new GridLayout(2, 1, 4, 4));
        panel.add(row1);
        panel.add(row2);
        dialog.add(panel);

        item.addActionListener(e ->
        {
            Point loc = frame.getLocation();
            dialog.setLocation((int) loc.getX(), (int) loc.getY() - 60);
            dialog.pack();
            dialog.setVisible(true);
        });
        return item;
    }

    // Packs working_image's pixels from the pristine channels, applying the
    // current smooth_level (bilateralSmooth) and smooth2_level
    // (anisotropicSmooth) per channel -- both starting fresh from
    // red_channel/green_channel/blue_channel every time, never chaining onto
    // a previously-smoothed result, exactly mirroring
    // DeltaWriter.ApplyHandler's "int[] ch = (int[]) channel_list.get(i); if
    // (smooth_level > 0) ch = DeltaMapper.bilateralSmooth(...); if
    // (smooth2_level > 0) ch = DeltaMapper.anisotropicSmooth(...);" pattern.
    // Does NOT touch display_image/repaint -- see rebuildWorkingImage()
    // below, used once image_canvas actually exists.
    private void repackWorkingImage()
    {
        int[] red   = red_channel;
        int[] green = green_channel;
        int[] blue  = blue_channel;

        if (smooth_level > 0)
        {
            red   = TranslateMapper.bilateralSmooth(red,   image_xdim, image_ydim, smooth_level);
            green = TranslateMapper.bilateralSmooth(green, image_xdim, image_ydim, smooth_level);
            blue  = TranslateMapper.bilateralSmooth(blue,  image_xdim, image_ydim, smooth_level);
        }
        if (smooth2_level > 0)
        {
            red   = TranslateMapper.anisotropicSmooth(red,   image_xdim, image_ydim, smooth2_level);
            green = TranslateMapper.anisotropicSmooth(green, image_xdim, image_ydim, smooth2_level);
            blue  = TranslateMapper.anisotropicSmooth(blue,  image_xdim, image_ydim, smooth2_level);
        }

        int k = 0;
        for (int j = 0; j < image_ydim; j++)
        {
            for (int i = 0; i < image_xdim; i++)
            {
                working_image.setRGB(i, j, (red[k] << 16) | (green[k] << 8) | blue[k]);
                k++;
            }
        }
    }

    // Used by the Smooth sliders (after the window is fully up): repacks
    // working_image at the current smooth_level/smooth2_level, then
    // immediately rescales and repaints -- DeltaWriter.ApplyHandler's live
    // "recompute and redisplay on every slider change" behavior.
    private void rebuildWorkingImage()
    {
        repackWorkingImage();
        updateDisplayImage();
        image_canvas.repaint();
    }

    // =========================================================================
    // Zoom / scroll (adapted directly from DeltaWriter.java)
    // =========================================================================

    private void zoomBy(double factor)
    {
        double ns = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom_scale * factor));
        if (ns == zoom_scale) return;
        JViewport vp = scroll_pane.getViewport();
        Point vpos = vp.getViewPosition();
        Dimension vs = vp.getSize();
        double cx = vpos.x + vs.width / 2.0, cy = vpos.y + vs.height / 2.0, r = ns / zoom_scale;
        zoom_scale = ns;
        updateDisplayImage();
        image_canvas.setPreferredSize(new Dimension((int) (image_xdim * zoom_scale), (int) (image_ydim * zoom_scale)));
        image_canvas.revalidate();
        image_canvas.repaint();
        vp.setViewPosition(new Point(Math.max(0, (int) (cx * r - vs.width / 2.0)), Math.max(0, (int) (cy * r - vs.height / 2.0))));
        updateTitle();
    }

    private void updateDisplayImage()
    {
        BufferedImage src = working_image;
        if (zoom_scale == 1.0) { display_image = src; return; }
        int w = Math.max(1, (int) (image_xdim * zoom_scale)), h = Math.max(1, (int) (image_ydim * zoom_scale));
        AffineTransform t = new AffineTransform();
        t.scale(zoom_scale, zoom_scale);
        display_image = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR).filter(src, new BufferedImage(w, h, src.getType()));
    }

    private void updateTitle()
    {
        frame.setTitle("Image Translater  " + filename + "  [" + (int) Math.round(zoom_scale * 100) + "%]");
    }

    class ImageCanvas extends JPanel
    {
        public ImageCanvas() { setOpaque(true); }

        @Override
        public Dimension getPreferredSize()
        {
            return display_image != null ? new Dimension(display_image.getWidth(), display_image.getHeight())
                                          : new Dimension(Math.max(1, (int) (image_xdim * zoom_scale)), Math.max(1, (int) (image_ydim * zoom_scale)));
        }

        @Override
        protected synchronized void paintComponent(Graphics g)
        {
            super.paintComponent(g);
            if (display_image != null) g.drawImage(display_image, 0, 0, this);
        }
    }

    // =========================================================================
    // File > Save -- adapted directly from getTranslatedImage.java: same crop
    // math, same output naming convention, same output directory (alongside
    // the input file, not the cwd). The only difference is the source pixels
    // come from working_image (the currently-displayed, possibly-smoothed
    // image) instead of freshly re-reading the input file.
    // =========================================================================

    private void saveCrops()
    {
        int width = image_xdim, height = image_ydim;
        int overlapWidth  = width  - Math.abs(dx);
        int overlapHeight = height - Math.abs(dy);
        if (overlapWidth <= 0 || overlapHeight <= 0)
        {
            JOptionPane.showMessageDialog(frame,
                "Shift (" + dx + "," + dy + ") leaves no overlap at all in a " + width + "x" + height + " image -- nothing to write.",
                "Save", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int refXoff   = Math.max(0, dx);
        int refYoff   = Math.max(0, dy);
        int shiftXoff = Math.max(0, -dx);
        int shiftYoff = Math.max(0, -dy);

        BufferedImage reference = crop(working_image, refXoff, refYoff, overlapWidth, overlapHeight);
        BufferedImage shifted   = crop(working_image, shiftXoff, shiftYoff, overlapWidth, overlapHeight);

        File outputDir = new File(filename).getAbsoluteFile().getParentFile();
        String base = baseNameWithoutExtension(filename);
        String ext  = extensionOf(filename);
        String referenceOutputPath = new File(outputDir, base + "_ref_"   + dx + "_" + dy + "." + ext).getPath();
        String shiftedOutputPath   = new File(outputDir, base + "_shift_" + dx + "_" + dy + "." + ext).getPath();

        boolean ok = writeImage(reference, referenceOutputPath) && writeImage(shifted, shiftedOutputPath);
        if (ok)
        {
            JOptionPane.showMessageDialog(frame,
                "Wrote two " + overlapWidth + "x" + overlapHeight + " images:\n"
                    + referenceOutputPath + "\n" + shiftedOutputPath,
                "Save", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private static BufferedImage crop(BufferedImage src, int xoff, int yoff, int w, int h)
    {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                dst.setRGB(col, row, src.getRGB(xoff + col, yoff + row));
            }
        }
        return dst;
    }

    private boolean writeImage(BufferedImage img, String path)
    {
        String formatName = formatNameFromPath(path);
        try
        {
            boolean wrote = ImageIO.write(img, formatName, new File(path));
            if (!wrote)
            {
                JOptionPane.showMessageDialog(frame, "No writer available for format \"" + formatName + "\" -- try saving from a .png-named source.", "Save", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            return true;
        }
        catch (IOException e)
        {
            JOptionPane.showMessageDialog(frame, "Could not write \"" + path + "\": " + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static String baseNameWithoutExtension(String path)
    {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? name : name.substring(0, dot);
    }

    private static String extensionOf(String path)
    {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1) ? "png" : name.substring(dot + 1).toLowerCase();
    }

    private static String formatNameFromPath(String path)
    {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return "png";
        String ext = path.substring(dot + 1).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) return "jpg";
        if (ext.equals("png")) return "png";
        if (ext.equals("bmp")) return "bmp";
        if (ext.equals("gif")) return "gif";
        return "png";
    }
}
