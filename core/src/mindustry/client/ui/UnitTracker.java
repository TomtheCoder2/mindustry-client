package mindustry.client.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.ui.layout.Stack;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.client.antigrief.*;
import mindustry.client.utils.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;
import static mindustry.client.ClientVars.*;
import static mindustry.logic.LCanvas.tooltip;

public class UnitTracker extends BaseDialog {
    private static final float tagh = 42f, entryh = 60f;
    public static final Seq<String> acceptedFields = new Seq<>();
    static Boolf<String> fieldValidator = str -> acceptedFields.contains(str.trim());
    static final Color accept = Colors.get("GREEN"), deny = Colors.get("RED");

    private Runnable rebuildPane = () -> {}, rebuildTags = () -> {}, rebuildCriteria, sortCriteria;

    //private final Seq<UnitType> selectedTags = new Seq<>();
    private final Bits selectedTags = new Bits(Vars.content.units().size);

    Table entryTable;
    public final Seq<SortEntry> sortEntries = new Seq<>(), sortEntriesTemp = new Seq<>();
    public int entries = 0;
    private final boolean[] rebuild = {false}, sorting = {false};
    private final long[] lastSortTime = {0L};
    @Nullable public UnitLog followedLog = null;
    private Sort threadSort = new Sort();

    public Table logTable;
    {
        Events.on(EventType.ClientLoadEvent.class, event -> Core.app.post(() -> { // help this is filthy
            rebuildPane = () -> {
                int cols = Math.max((int) (Core.graphics.getWidth() / Scl.scl(400)), 1);

                logTable.clear();
                logTable.defaults().margin(5f).pad(5f).growY();
                int[] i = {0};
                synchronized (shownTrackedUnits) {
                    shownTrackedUnits.each(log -> {
                        var pair = log.getView(logTable, false);
                        var orig = pair.second;
                        //TODO: avoid recreating this from scratch all the time
                        pair.first.self(it -> {
                            //do something to it
                            it.get().clicked(() -> { if(Core.input.ctrl()) setFollowedLog(log.getViewFollowing() ? null : log); });
                            orig.get(it);
                        });

                        if (++i[0] % cols == 0) {
                            logTable.row();
                        }
                    });
                }
                synchronized (rebuild) {
                    rebuild[0] = false;
                }
                if(followedLog != null && logTable.hasParent() && logTable.parent instanceof ScrollPane pane) {
                    Core.app.post(() -> {
                        float y = followedLog.getView().localToParentCoordinates(Tmp.v1.set(0, followedLog.getView().getHeight()/2f)).y;
                        pane.setScrollY(logTable.getHeight() - y - pane.getHeight()/2f);
                        pane.updateVisualScroll();
                    });
                }
            };

            rebuildPane.run();

            acceptedFields.addAll(Vars.content.items().<String>map(i -> "@" + i.name));
            acceptedFields.addAll(new Seq<>(LAccess.senseable).<String>map(s -> "@" + s.name()));
            acceptedFields.removeAll(it -> it.toLowerCase().contains("liquid") || it.toLowerCase().contains("power"));
            Seq<String> toRemove = new Seq<>();
            toRemove.addAll("@heat", "@enabled", "@config");
            acceptedFields.removeAll(toRemove);
        }));

    }
    {
        rebuildCriteria = () -> {
            entryTable.clear();
            if(entries == 0){
                entryTable.button("Add criteria", () -> {
                    new SortEntry().addLoc();
                    rebuildCriteria.run();
                }).growX().height(entryh);
            } else {
                sortEntries.each(se -> {
                    entryTable.add(se).expandY().fillX().align(Align.left);
                    entryTable.row();
                });
            }
        };

        sortCriteria = () -> {
            synchronized (sorting) {
                if(sorting[0]) return;
                sorting[0] = true;
            }
            synchronized (lastSortTime) {
                lastSortTime[0] = Time.millis();
            }
            Log.debug(Time.millis());
            synchronized (sortEntries) {
//                if(sortEntries.isEmpty()){
//                    synchronized (sorting) {
//                        sorting[0] = false;
//                        return;
//                    }
//                } //maybe if the thing actually gets that optimized
                sortEntriesTemp.set(sortEntries);
            }
            sortEntriesTemp.filter(t -> t.getAccessVariable() != null);
            //Seq<UnitLog> shownTrackedUnitsTemp = new Seq<>(UnitLog.class);
            shownTrackedUnitsTemp.clear();
            synchronized (trackedUnits) {
                for (int i=0; i < trackedUnits.size; i++){
                    synchronized (selectedTags) {
                        if (!selectedTags.isEmpty() && !selectedTags.get(i)) continue;
                    }
                    shownTrackedUnitsTemp.addAll(trackedUnits.get(i).values());
                }
            }
            shownTrackedUnitsTemp.filter(it -> {
                // filtered unit types
                for (SortEntry entry : sortEntriesTemp) {
                    if (!entry.within(it)) return false;
                }
                return true;
            });
            shownTrackedUnitsTemp.each(ul -> ul.senseSnapshot.setSize(sortEntriesTemp.size));
            for(int i = 0; i < sortEntriesTemp.size; i++){
                int fi = i;
                shownTrackedUnitsTemp.each(ul -> ul.senseSnapshot.set(fi, ul.sense(sortEntriesTemp.get(fi).getAccessVariable().objval)));
            }
            shownTrackedUnitsTemp.each(UnitLog::updateSnapshotText);
            try {
                threadSort.sort(shownTrackedUnitsTemp.items, (a, b) -> {
                    for (int i = 0; i < sortEntriesTemp.size; i++) {
                        int diff = a.senseSnapshot.get(i).compareTo(b.senseSnapshot.get(i));
                        if (diff == 0) continue;
                        return diff;
                    }
                    return 0;
                }, 0, shownTrackedUnitsTemp.size);
            } catch (Exception e){
                Log.err(e);
            }
            synchronized (shownTrackedUnits) {
                shownTrackedUnits.set(shownTrackedUnitsTemp);
            }
            synchronized (rebuild) {
                rebuild[0] = true;
            }
            synchronized (sorting) {
                sorting[0] = false;
            }
        };
    }

    public UnitTracker(){
        super("@client.unittracker");
        addCloseButton();
        shown(() -> {
            setup();
            clientThread.taskQueue.post(sortCriteria);
        });
        onResize(this::setup);
        setup();
        //hidden(clear everything);
    }

    @Override
    public void act(float delta) {
        synchronized (rebuild) {
            if (rebuild[0]) rebuildPane.run();
        }
        synchronized (lastSortTime) {
            synchronized (sorting) {
                if (!sorting[0] && Time.timeSinceMillis(lastSortTime[0]) >= Core.settings.getInt("trackertime", 1000)) {
                    clientThread.taskQueue.post(sortCriteria);
                }
            }
        }
        super.act(delta);
    }

    void setup(){
        cont.top();
        cont.clear();

        sorting[0] = false;

        cont.table(in -> {
            in.left();
            in.pane(Styles.nonePane, t -> {
                rebuildTags = () -> {
                    t.clearChildren();
                    t.left();

                    t.defaults().pad(2).height(tagh);
                    for(var tag : content.units()){
                        int id = tag.id;
                        t.button(Fonts.getUnicodeStr(tag.name), Styles.togglet, () -> {
                            synchronized (selectedTags) {
                                if (selectedTags.get(id)) {
                                    selectedTags.clear(id);
                                } else {
                                    selectedTags.set(id);
                                }
                            }
                            rebuildPane.run();
                        }).checked(selectedTags.get(id)).with(c -> c.getLabel().setWrap(false));
                    }
                };
                rebuildTags.run();
            }).fillX().height(tagh).scrollY(false);
        }).height(tagh).fillX();

        cont.row();
        if(entryTable != null) entryTable.clear();
        entryTable = cont.table().growX().expandY().get();
        rebuildCriteria.run();
        cont.row();
        cont.pane(t -> {
            logTable = t;
            t.top();
            rebuildPane.run();
        }).grow().scrollX(false);
    }

    public void setFollowedLog(@Nullable UnitLog next){
        if(followedLog != null) followedLog.setViewFollowing(false);
        if(next != null) next.setViewFollowing(true);
        followedLog = next;
    }

    public class SortEntry extends Table{
        String filterType = "";
        TextField textField;
        int selected = 0;
        int index;
        public volatile LExecutor.Var accessVariable = null;

        public synchronized void setAccessVariable(LExecutor.Var av){
            this.accessVariable = av;
        }

        public synchronized LExecutor.Var getAccessVariable(){
            return accessVariable;
        }

        private void build(){
            defaults().pad(0f, 5f, 0f, 5f);
            left();
            table(this::rebuildSortCriteria).expandY().align(Align.left);
            add().growX();
            button(Icon.add, Styles.cleari, () -> {
                new SortEntry().addLoc(index+1);
                rebuildCriteria.run();
            }).grow();
            button(Icon.cancel, Styles.cleari, () -> {
                removeLoc(index);
                rebuildCriteria.run();
            }).grow();
        }

        public Table addLoc(){
            return addLoc(entries);
        }

        private Table addLoc(int loc){
            index = Mathf.clamp(loc, 0, entries);
            synchronized (sortEntries) {
                for (int i = index; i < entries; i++) {
                    sortEntries.get(i).index++;
                }
                sortEntries.insert(index, this);
            }
            entries++;
            build();
            return this;
        }

        private void removeLoc(int loc){
            synchronized (sortEntries) {
                for (int i = loc + 1; i < entries; i++) {
                    sortEntries.get(i).index--;
                }
                sortEntries.remove(loc);
            }
            entries--;
        }

        public boolean within(UnitLog it){
            //TODO: pass
            return true;
        }

        protected void showSelectTable(Button b, Cons2<Table, Runnable> hideCons){
            Table t = new Table(Tex.paneSolid){
                @Override
                public float getPrefHeight(){
                    return Math.min(super.getPrefHeight(), Core.graphics.getHeight());
                }

                @Override
                public float getPrefWidth(){
                    return Math.min(super.getPrefWidth(), Core.graphics.getWidth());
                }
            };
            t.margin(4);

            //triggers events behind the element to simulate deselection
            Element hitter = new Element();

            Runnable hide = () -> {
                Core.app.post(hitter::remove);
                t.actions(Actions.fadeOut(0.3f, Interp.fade), Actions.remove());
            };

            hitter.fillParent = true;
            hitter.tapped(hide);

            Core.scene.add(hitter);
            Core.scene.add(t);

            t.update(() -> {
                if(b.parent == null || !b.isDescendantOf(Core.scene.root)){
                    Core.app.post(() -> {
                        hitter.remove();
                        t.remove();
                    });
                    return;
                }

                b.localToStageCoordinates(Tmp.v1.set(b.getWidth()/2f, b.getHeight()/2f));
                t.setPosition(Tmp.v1.x, Tmp.v1.y, Align.center);
                if(t.getWidth() > Core.scene.getWidth()) t.setWidth(Core.graphics.getWidth());
                if(t.getHeight() > Core.scene.getHeight()) t.setHeight(Core.graphics.getHeight());
                t.keepInStage();
                t.invalidateHierarchy();
                t.pack();
            });
            t.actions(Actions.alpha(0), Actions.fadeIn(0.3f, Interp.fade));

            t.top().pane(inner -> {
                inner.top();
                hideCons.get(inner, hide);
            }).pad(0f).top().scrollX(false);

            t.pack();
        }

        private void rebuildSortCriteria(Table entry){
            entry.clearChildren();
            entry.align(Align.left | Align.center);
            entry.label(() -> "Criteria " + (index + 1) + ":");

            var field = textField = entry.field(filterType, Styles.nodeField, str -> filterType = str).size(288f, 40f).pad(2f).maxTextLength(LAssembler.maxTokenLength).padRight(0f).get();
            textField.typed(chr -> field.setColor(field.getText().trim().isEmpty()? Color.white : fieldValidator.get(field.getText())? accept : deny));
            textField.addListener(new FocusListener(){
                @Override
                public void keyboardFocusChanged(FocusListener.FocusEvent event, Element element, boolean focused){
                    if(!focused) textUpdated(false);
                }
            });
            entry.button(b -> {
                b.image(Icon.pencilSmall);
                b.clicked(() -> showSelectTable(b, (t2, hide) -> {
                    Table[] tables = {
                        //items
                        new Table(i -> {
                            i.left();
                            int c = 0;
                            for (Item item : content.items()) {
                                if (!item.unlockedNow()) continue;
                                i.button(new TextureRegionDrawable(item.uiIcon), Styles.cleari, iconSmall, () -> {
                                    stype("@" + item.name);
                                    hide.run();
                                }).size(40f);

                                if (++c % 6 == 0) i.row();
                            }
                        }),
                        //sensors
                        new Table(i -> {
                            for (LAccess sensor : LAccess.senseable) {
                                if(!acceptedFields.contains("@" + sensor.name())) continue;
                                i.button(sensor.name(), Styles.cleart, () -> {
                                    stype("@" + sensor.name());
                                    hide.run();
                                }).size(240f, 40f).self(c -> tooltip(c, sensor)).row();
                            }
                        })
                    };
                    Drawable[] icons = {Icon.box, Icon.tree};
                    Stack stack = new Stack(tables[selected]);
                    ButtonGroup<Button> group = new ButtonGroup<>();

                    for (int i = 0; i < tables.length; i++) {
                        int fi = i;

                        t2.button(icons[i], Styles.clearTogglei, () -> {
                            selected = fi;

                            stack.clearChildren();
                            stack.addChild(tables[selected]);

                            t2.parent.parent.pack();
                            t2.parent.parent.invalidateHierarchy();
                        }).height(50f).growX().checked(selected == fi).group(group);
                    }
                    t2.row();
                    t2.add(stack).colspan(3).width(240f).left();
                }));
            }, Styles.logict, () -> {
            }).size(40f).padLeft(-1).self(b -> { if(b.hasElement()) b.get().update(() -> b.get().setColor(field.color)); });
        }

        private void stype(String text){
            textField.setText(filterType = text);
            textField.setColor(accept);
            textUpdated(true);
        }

        private void textUpdated(boolean verified){ // if it has been checked AND is valid
            boolean valid = verified || fieldValidator.get(textField.getText());
            setAccessVariable(valid ? constants.get(constants.get(textField.getText())) : null); // uh? is there a better way
            //Log.debug("Updated as @, prev ID: @ (@), current ID: @ (@)", textField.getText(), prevId, prevId == -1 ? "null" : constants.get(prevId), accessVariable, accessVariable == -1 ? "null" : constants.get(accessVariable));
        }
    }
}
