package nl.enjarai.recursiveresources.gui;

import com.google.common.collect.Lists;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackListWidget;
import net.minecraft.client.gui.screen.pack.PackListWidget.ResourcePackEntry;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;
import nl.enjarai.recursiveresources.RecursiveResources;
import nl.enjarai.recursiveresources.pack.FolderMeta;
import nl.enjarai.recursiveresources.pack.FolderPack;
import nl.enjarai.recursiveresources.util.ResourcePackListProcessor;
import nl.enjarai.recursiveresources.util.ResourcePackUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static nl.enjarai.recursiveresources.gui.ResourcePackFolderEntry.WIDGETS_TEXTURE;
import static nl.enjarai.recursiveresources.util.ResourcePackUtils.isFolderButNotFolderBasedPack;

public class FolderedResourcePackScreen extends PackScreen {
    private static final Path ROOT_FOLDER = Path.of("");

    private static final Text OPEN_PACK_FOLDER = Text.translatable("pack.openFolder");
    private static final Text DONE = Text.translatable("gui.done");
    private static final Text SORT_AZ = Text.translatable("recursiveresources.sort.a-z");
    private static final Text SORT_ZA = Text.translatable("recursiveresources.sort.z-a");
    private static final Text VIEW_FOLDER = Text.translatable("recursiveresources.view.folder");
    private static final Text VIEW_FLAT = Text.translatable("recursiveresources.view.flat");

    private final MinecraftClient client = MinecraftClient.getInstance();

    private final ResourcePackListProcessor listProcessor = new ResourcePackListProcessor(this::refresh);
    private Comparator<ResourcePackEntry> currentSorter;

    private PackListWidget originalAvailablePacks;
    private FolderedPackListWidget customAvailablePacks;
    private TextFieldWidget searchField;

    private Path currentFolder = ROOT_FOLDER;
    private FolderMeta currentFolderMeta;
    private boolean folderView = true;
    public final List<Path> roots;

    public FolderedResourcePackScreen(Screen parent, ResourcePackManager packManager, Consumer<ResourcePackManager> applier, File mainRoot, Text title, List<Path> roots) {
        super(parent, packManager, applier, mainRoot, title);
        this.roots = roots;
        this.currentFolderMeta = FolderMeta.loadMetaFile(roots, currentFolder);
        this.currentSorter = (pack1, pack2) -> Integer.compare(
                currentFolderMeta.sortEntry(pack1, currentFolder),
                currentFolderMeta.sortEntry(pack2, currentFolder)
        );
    }

    // Components

    @Override
    protected void init() {
        client.keyboard.setRepeatEvents(true);
        super.init();

        findButton(OPEN_PACK_FOLDER).ifPresent(btn -> {
            btn.x = width / 2 + 25;
            btn.y = height - 48;
        });

        findButton(DONE).ifPresent(btn -> {
            btn.x = width / 2 + 25;
            btn.y = height - 26;
        });

        addDrawableChild(new ButtonWidget(width / 2 - 179 + 68, height - 26, 86, 20, folderView ? VIEW_FOLDER : VIEW_FLAT, btn -> {
            folderView = !folderView;
            btn.setMessage(folderView ? VIEW_FOLDER : VIEW_FLAT);

            refresh();
            customAvailablePacks.setScrollAmount(0.0);
        }));

        // Load all available packs button
        addDrawableChild(new SilentTexturedButtonWidget(width / 2 - 204, 0, 32, 32, 0, 0, WIDGETS_TEXTURE, btn -> {
            for (ResourcePackEntry entry : Lists.reverse(List.copyOf(availablePackList.children()))) {
                if (entry.pack.canBeEnabled()) {
                    entry.pack.enable();
                }
            }
        }));

        // Unload all button
        addDrawableChild(new SilentTexturedButtonWidget(width / 2 + 204 - 32, 0, 32, 32, 32, 0, WIDGETS_TEXTURE, btn -> {
            for (ResourcePackEntry entry : List.copyOf(selectedPackList.children())) {
                if (entry.pack.canBeDisabled()) {
                    entry.pack.disable();
                }
            }
        }));

        searchField = addDrawableChild(new TextFieldWidget(
                textRenderer, width / 2 - 179, height - 46, 154, 16, searchField, Text.of("")));
        searchField.setFocusUnlocked(true);
        searchField.setChangedListener(listProcessor::setFilter);
        addDrawableChild(searchField);

        // Replacing the available pack list with our custom implementation
        originalAvailablePacks = availablePackList;
        remove(originalAvailablePacks);
        addSelectableChild(customAvailablePacks = new FolderedPackListWidget(originalAvailablePacks, 200, height, width / 2 - 204));
        availablePackList = customAvailablePacks;

        listProcessor.pauseCallback();
        listProcessor.setSorter(currentSorter == null ? (currentSorter = ResourcePackListProcessor.sortAZ) : currentSorter);
        listProcessor.setFilter(searchField.getText());
        listProcessor.resumeCallback();
    }

    private Optional<ClickableWidget> findButton(Text text) {
        return children.stream()
                .filter(ClickableWidget.class::isInstance)
                .map(ClickableWidget.class::cast)
                .filter(btn -> text.equals(btn.getMessage()))
                .findFirst();
    }

    @Override
    public void updatePackLists() {
        super.updatePackLists();
        if (customAvailablePacks != null) {
            onFiltersUpdated();
        }
    }

    // Processing

    private Path getParentFileSafe(Path file) {
        var parent = file.getParent();
        return parent == null ? ROOT_FOLDER : parent;
    }

    private boolean notInRoot() {
        return folderView && !currentFolder.equals(ROOT_FOLDER);
    }

    private void onFiltersUpdated() {
        List<ResourcePackEntry> folders = null;

        if (folderView) {
            folders = new ArrayList<>();

            // add a ".." entry when not in the root folder
            if (notInRoot()) {
                folders.add(new ResourcePackFolderEntry(client, customAvailablePacks,
                        this, getParentFileSafe(currentFolder), true));
            }

            // create entries for all the folders that aren't packs
            var createdFolders = new ArrayList<Path>();
            for (Path root : roots) {
                var absolute = root.resolve(currentFolder);

                try (var contents = Files.list(absolute)) {
                    for (Path folder : contents.filter(ResourcePackUtils::isFolderButNotFolderBasedPack).toList()) {
                        var relative = root.relativize(folder.normalize());

                        if (createdFolders.contains(relative)) {
                            continue;
                        }

                        var entry = new ResourcePackFolderEntry(client, customAvailablePacks,
                                this, relative);

                        if (((FolderPack) entry.pack).isVisible()) {
                            folders.add(entry);
                        }
                        createdFolders.add(relative);
                    }
                } catch (IOException e) {
                    RecursiveResources.LOGGER.error("Failed to read contents of " + absolute, e);
                }
            }
        }

        listProcessor.apply(customAvailablePacks.children().stream().toList(), folders, customAvailablePacks.children());

        // filter out all entries that aren't in the current folder
        if (folderView) {
            var filteredPacks = customAvailablePacks.children().stream().filter(entry -> {
                // if it's a folder, it's already relative, so we can check easily
                if (entry instanceof ResourcePackFolderEntry folder) {
                    return folder.isUp || currentFolder.equals(getParentFileSafe(folder.folder));
                }

                // if it's a pack, we can use the foldermeta to check if it should be shown
                return currentFolderMeta.containsEntry(entry, currentFolder);
            }).toList();

            customAvailablePacks.children().clear();
            customAvailablePacks.children().addAll(filteredPacks);
        }

        customAvailablePacks.setScrollAmount(customAvailablePacks.getScrollAmount());
    }

    public void moveToFolder(Path folder) {
        currentFolder = folder;
        currentFolderMeta = FolderMeta.loadMetaFile(roots, currentFolder);
        refresh();
        customAvailablePacks.setScrollAmount(0.0);
    }

    // UI Overrides

    @Override
    public void tick() {
        super.tick();
        searchField.tick();
    }

    @Override
    public void removed() {
        super.removed();
        client.keyboard.setRepeatEvents(false);
    }
}
