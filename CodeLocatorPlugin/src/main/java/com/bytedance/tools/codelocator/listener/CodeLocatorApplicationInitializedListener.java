package com.bytedance.tools.codelocator.listener;

import com.android.ddmlib.IDevice;
import com.bytedance.tools.codelocator.device.Device;
import com.bytedance.tools.codelocator.device.DeviceManager;
import com.bytedance.tools.codelocator.model.CodeLocatorUserConfig;
import com.bytedance.tools.codelocator.model.ColorInfo;
import com.bytedance.tools.codelocator.tinypng.actions.TinyImageMenuAction;
import com.bytedance.tools.codelocator.utils.*;
import com.google.gson.reflect.TypeToken;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.ide.ApplicationInitializedListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.impl.ProjectLifecycleListener;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.*;
import com.intellij.ui.LightweightHint;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.*;

public class CodeLocatorApplicationInitializedListener implements ApplicationInitializedListener {

    public static final int HINT_ITEM_HEIGHT = 25;

    private String lastSelectText = null;

    private static List<ColorInfo> sColorInfo;

    private HashSet<ColorInfo> mFindColorSets = new HashSet<>();

    private int maxWidth;

    public static void setColorInfo(List<ColorInfo> colorInfo) {
        if (colorInfo == null || colorInfo.isEmpty()) {
            return;
        }
        sColorInfo = colorInfo;
        ThreadUtils.submit(() -> {
            try {
                FileUtils.saveContentToFile(new File(FileUtils.sCodeLocatorMainDirPath, FileUtils.GRAPH_COLOR_DATA_FILE_NAME), GsonUtils.sGson.toJson(colorInfo));
            } catch (Throwable t) {
                Log.e("保存Color数据失败", t);
            }
        });
    }

    @Override
    public void componentsInitialized() {
        FileUtils.init();
        initColorInfo();
        registerEditColorLisenter();
        registerVirtualFileListener();

        ThreadUtils.submit(() -> {
            NetUtils.fetchConfig();
        });

        ApplicationManager.getApplication().getMessageBus().connect().subscribe(ProjectLifecycleListener.TOPIC, new ProjectLifecycleListener() {
            @Override
            public void afterProjectClosed(@NotNull Project project) {
                final Device currentDevice = DeviceManager.getCurrentDevice(project);
                if (currentDevice != null && currentDevice.getDevice() != null) {
                    final String serialNumber = currentDevice.getDevice().getSerialNumber();
                    CodeLocatorUserConfig.loadConfig().setLastDevice(serialNumber);
                    DeviceManager.onProjectClose(project);
                } else if (currentDevice == null) {
                    final IDevice device = DeviceManager.onProjectClose(project);
                    if (device != null) {
                        CodeLocatorUserConfig.loadConfig().setLastDevice(device.getSerialNumber());
                    }
                } else {
                    DeviceManager.onProjectClose(project);
                }
                CodeLocatorUserConfig.updateConfig(CodeLocatorUserConfig.loadConfig());
            }
        });
    }

    public static LinkedList<VirtualFile> sAddImageFiles = new LinkedList<>();

    private void registerVirtualFileListener() {
        VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
            @Override
            public void fileCreated(@NotNull VirtualFileEvent event) {
                if (!CodeLocatorUserConfig.loadConfig().isSupportTinyPng() || !(event instanceof VirtualFileCopyEvent)) {
                    return;
                }
                super.fileCreated(event);
                final VirtualFile file = event.getFile();
                if (!TinyImageMenuAction.sPredicate.test(file)) {
                    return;
                }
                sAddImageFiles.add(file);
            }
        });
    }

    private void registerEditColorLisenter() {
        if (EditorFactory.getInstance().getEventMulticaster() != null) {
            EditorFactory.getInstance().getEventMulticaster().addSelectionListener(new SelectionListener() {
                @Override
                public void selectionChanged(@NotNull SelectionEvent e) {
                    if (!CodeLocatorUserConfig.loadConfig().isPreviewColor()) {
                        return;
                    }
                    if (e.getNewRange().getLength() <= 1 || e.getNewRange().getLength() > 50) {
                        lastSelectText = null;
                        return;
                    }
                    final String selectText = e.getEditor().getDocument().getText(e.getNewRange());
                    String text = selectText.trim();
                    if (e.getNewRange().getStartOffset() - 8 >= 0) {
                        text = e.getEditor().getDocument().getText(new TextRange(e.getNewRange().getStartOffset() - 8, e.getNewRange().getEndOffset())).trim();
                    }
                    String colorStr = null;
                    if (text.contains("R.color.")) {
                        colorStr = text.substring(text.indexOf("R.color.") + "R.color.".length()).trim();
                    } else if (text.contains("@color/")) {
                        colorStr = text.substring(text.indexOf("@color/") + "@color/".length()).trim();
                    }
                    if (colorStr == null || colorStr.equals(lastSelectText)) {
                        return;
                    }
                    if (sColorInfo == null) {
                        return;
                    }
                    lastSelectText = colorStr;
                    final HashSet<ColorInfo> colorInfos = getColorInfos(colorStr);
                    if (colorInfos.isEmpty()) {
                        return;
                    }
                    final Editor editor = e.getEditor();
                    final FontMetrics fontMetrics = e.getEditor().getComponent().getFontMetrics(e.getEditor().getComponent().getFont());
                    final JComponent colorInfosPanel = getColorInfosPanel(colorInfos, fontMetrics);
                    final LightweightHint lightweightHint = new LightweightHint(colorInfosPanel);
                    final Point point = HintManagerImpl.getInstanceImpl().getHintPosition(lightweightHint, editor, HintManager.ABOVE);
                    point.y -= ((colorInfos.size() - 1) * CoordinateUtils.TABLE_RIGHT_MARGIN + colorInfos.size() * HINT_ITEM_HEIGHT + CoordinateUtils.TABLE_RIGHT_MARGIN);
                    lastSelectText = null;
                    HintManagerImpl.getInstanceImpl()
                        .showHint(colorInfosPanel,
                            new RelativePoint(e.getEditor().getComponent().getRootPane(), point),
                            HintManager.HIDE_BY_ANY_KEY | HintManager.HIDE_BY_TEXT_CHANGE | HintManager.HIDE_BY_OTHER_HINT | HintManager.HIDE_BY_SCROLLING, 0);
                    Mob.mob(Mob.Action.CLICK, Mob.Button.COLOR_MODE);
                }
            });
        }
    }

    private void initColorInfo() {
        final String fileContent = FileUtils.getFileContent(new File(FileUtils.sCodeLocatorMainDirPath, FileUtils.GRAPH_COLOR_DATA_FILE_NAME));
        if (fileContent == null || fileContent.isEmpty()) {
            return;
        }
        try {
            List<ColorInfo> colorInfos = GsonUtils.sGson.fromJson(fileContent, new TypeToken<List<ColorInfo>>() {
            }.getType());
            if (colorInfos != null) {
                sColorInfo = colorInfos;
            }
        } catch (Throwable t) {
            Log.e("恢复Color失败", t);
            FileUtils.deleteFile(new File(FileUtils.sCodeLocatorMainDirPath, FileUtils.GRAPH_COLOR_DATA_FILE_NAME));
        }
    }

    private JComponent getColorInfosPanel(HashSet<ColorInfo> colorInfos, FontMetrics fontMetrics) {
        maxWidth = 0;
        final int size = colorInfos.size();
        final JPanel jPanel = new JPanel();
        jPanel.setLayout(new BoxLayout(jPanel, BoxLayout.Y_AXIS));
        if (size == 1) {
            jPanel.add(getSingleColorInfoPanel(colorInfos.iterator().next(), false, fontMetrics));
        } else {
            ArrayList<ColorInfo> colorInfoArrayList = new ArrayList<>(colorInfos);
            colorInfoArrayList.sort(Comparator.comparing(ColorInfo::getColorMode));
            for (ColorInfo colorInfo : colorInfoArrayList) {
                if (jPanel.getComponentCount() != 0) {
                    jPanel.add(Box.createVerticalStrut(CoordinateUtils.TABLE_RIGHT_MARGIN));
                }
                jPanel.add(getSingleColorInfoPanel(colorInfo, true, fontMetrics));
            }
        }
        for (int i = 0; i < jPanel.getComponentCount(); i++) {
            if (jPanel.getComponent(i) instanceof JPanel) {
                JComponentUtils.setSize((JComponent) jPanel.getComponent(i), maxWidth + 2 * CoordinateUtils.DEFAULT_BORDER + HINT_ITEM_HEIGHT, HINT_ITEM_HEIGHT);
                JComponentUtils.setSize((JComponent) ((JComponent) jPanel.getComponent(i)).getComponent(1), maxWidth, HINT_ITEM_HEIGHT);
            }
        }
        JComponentUtils.setMinimumHeight(jPanel, (maxWidth + 2 * CoordinateUtils.DEFAULT_BORDER + HINT_ITEM_HEIGHT), HINT_ITEM_HEIGHT * size + (size - 1) * CoordinateUtils.TABLE_RIGHT_MARGIN);
        return jPanel;
    }

    private JComponent getSingleColorInfoPanel(ColorInfo colorInfo, boolean needMode, FontMetrics fontMetrics) {
        final JPanel jPanel = new JPanel();
        jPanel.setLayout(new BoxLayout(jPanel, BoxLayout.X_AXIS));
        final String displayText = (needMode ? colorInfo.getColorMode() + " " : "") + ResUtils.getString("color_value_format", CodeLocatorUtils.toHexStr(colorInfo.getColor()));
        final JLabel jLabel = new JLabel(displayText);
        jPanel.add(Box.createHorizontalStrut(CoordinateUtils.DEFAULT_BORDER));
        jPanel.add(jLabel);
        final int width = fontMetrics.stringWidth(displayText);
        maxWidth = Math.max(maxWidth, width);
        final JPanel colorLabel = new JPanel();
        jPanel.add(Box.createHorizontalStrut(CoordinateUtils.DEFAULT_BORDER));
        JComponentUtils.setSize(colorLabel, 20, 20);
        colorLabel.setAlignmentY(0.5f);
        colorLabel.setBackground(new Color(colorInfo.getColor(), true));
        colorLabel.setBorder(BorderFactory.createLineBorder(Color.RED));
        jPanel.add(colorLabel);
        return jPanel;
    }

    private HashSet<ColorInfo> getColorInfos(String colorStr) {
        mFindColorSets.clear();
        for (int i = 0; i < sColorInfo.size(); i++) {
            if (sColorInfo.get(i) != null && colorStr.equals(sColorInfo.get(i).getColorName())) {
                mFindColorSets.add(sColorInfo.get(i));
                System.out.println(sColorInfo.get(i).getColorMode() + " " + sColorInfo.get(i).getColor() + " " + sColorInfo.get(i).getColorName());
            }
        }
        return mFindColorSets;
    }

}
