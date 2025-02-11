// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import com.intellij.AppTopics;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.startup.ServiceNotReadyException;
import com.intellij.model.ModelBranch;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.AppUIExecutor;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.EditorHighlighterCache;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import com.intellij.openapi.vfs.newvfs.ManagingFS;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.util.FlushingDaemon;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiDocumentTransactionListener;
import com.intellij.psi.impl.cache.impl.id.PlatformIdTableBuilding;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FactoryMap;
import com.intellij.util.gist.GistManager;
import com.intellij.util.indexing.contentQueue.CachedFileContent;
import com.intellij.util.indexing.diagnostic.FileIndexingStatistics;
import com.intellij.util.indexing.impl.MapReduceIndex;
import com.intellij.util.indexing.impl.storage.*;
import com.intellij.util.indexing.roots.IndexableFilesContributor;
import com.intellij.util.indexing.snapshot.SnapshotHashEnumeratorService;
import com.intellij.util.indexing.snapshot.SnapshotInputMappings;
import com.intellij.util.io.storage.HeavyProcessLatch;
import com.intellij.util.messages.SimpleMessageBusConnection;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileBasedIndexImpl extends FileBasedIndexEx {
  private static final ThreadLocal<VirtualFile> ourIndexedFile = new ThreadLocal<>();
  private static final ThreadLocal<VirtualFile> ourFileToBeIndexed = new ThreadLocal<>();
  public static final Logger LOG = Logger.getInstance(FileBasedIndexImpl.class);

  private volatile RegisteredIndexes myRegisteredIndexes;

  private final PerIndexDocumentVersionMap myLastIndexedDocStamps = new PerIndexDocumentVersionMap();

  // findExtensionOrFail is thread safe
  private final NotNullLazyValue<ChangedFilesCollector> myChangedFilesCollector =
    NotNullLazyValue.createValue(() -> AsyncEventSupport.EP_NAME.findExtensionOrFail(ChangedFilesCollector.class));

  private final List<IndexableFileSet> myIndexableSets = ContainerUtil.createLockFreeCopyOnWriteList();
  private final Map<IndexableFileSet, Project> myIndexableSetToProjectMap = new HashMap<>();

  private final SimpleMessageBusConnection myConnection;
  private final FileDocumentManager myFileDocumentManager;

  private final Set<ID<?, ?>> myUpToDateIndicesForUnsavedOrTransactedDocuments = ContainerUtil.newConcurrentSet();
  private volatile SmartFMap<Document, PsiFile> myTransactionMap = SmartFMap.emptyMap();

  private final boolean myIsUnitTestMode;

  private @Nullable Runnable myShutDownTask;
  private @Nullable ScheduledFuture<?> myFlushingFuture;

  private final AtomicInteger myLocalModCount = new AtomicInteger();
  private final AtomicInteger myFilesModCount = new AtomicInteger();
  private final Set<Project> myProjectsBeingUpdated = ContainerUtil.newConcurrentSet();

  private final Lock myReadLock;
  final Lock myWriteLock;

  private IndexConfiguration getState() {
    return myRegisteredIndexes.getConfigurationState();
  }

  void dropRegisteredIndexes() {
    ScheduledFuture<?> flushingFuture = myFlushingFuture;
    LOG.assertTrue(flushingFuture == null || flushingFuture.isCancelled() || flushingFuture.isDone());
    LOG.assertTrue(myUpToDateIndicesForUnsavedOrTransactedDocuments.isEmpty());
    LOG.assertTrue(myProjectsBeingUpdated.isEmpty());
    LOG.assertTrue(!getChangedFilesCollector().isUpdateInProgress());
    LOG.assertTrue(myTransactionMap.isEmpty());

    myRegisteredIndexes = null;
  }

  public FileBasedIndexImpl() {
    ReadWriteLock lock = new ReentrantReadWriteLock();
    myReadLock = lock.readLock();
    myWriteLock = lock.writeLock();

    myFileDocumentManager = FileDocumentManager.getInstance();
    myIsUnitTestMode = ApplicationManager.getApplication().isUnitTestMode();

    SimpleMessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().simpleConnect();

    connection.subscribe(PsiDocumentTransactionListener.TOPIC, new PsiDocumentTransactionListener() {
      @Override
      public void transactionStarted(@NotNull final Document doc, @NotNull final PsiFile file) {
        myTransactionMap = myTransactionMap.plus(doc, file);
        clearUpToDateIndexesForUnsavedOrTransactedDocs();
      }

      @Override
      public void transactionCompleted(@NotNull final Document doc, @NotNull final PsiFile file) {
        myTransactionMap = myTransactionMap.minus(doc);
      }
    });

    connection.subscribe(FileTypeManager.TOPIC, new FileBasedIndexFileTypeListener());

    connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new FileDocumentManagerListener() {
      @Override
      public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
        cleanupMemoryStorage(true);
      }

      @Override
      public void unsavedDocumentsDropped() {
        cleanupMemoryStorage(false);
      }
    });

    connection.subscribe(AppLifecycleListener.TOPIC, new AppLifecycleListener() {
      @Override
      public void appWillBeClosed(boolean isRestart) {
        if (myRegisteredIndexes != null && !myRegisteredIndexes.areIndexesReady()) {
          new Task.Modal(null, IndexingBundle.message("indexes.preparing.to.shutdown.message"), false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              myRegisteredIndexes.waitUntilAllIndicesAreInitialized();
            }
          }.queue();
        }
      }
    });

    myConnection = connection;

    FileBasedIndexExtension.EXTENSION_POINT_NAME.addExtensionPointListener(new ExtensionPointListener<>() {
      @Override
      public void extensionRemoved(@NotNull FileBasedIndexExtension<?, ?> extension, @NotNull PluginDescriptor pluginDescriptor) {
        ID.unloadId(extension.getName());
      }
    }, null);
  }

  void scheduleFullIndexesRescan(@NotNull Collection<ID<?, ?>> indexesToRebuild, @NotNull String reason) {
    cleanupProcessedFlag();
    doClearIndices(id -> indexesToRebuild.contains(id));
    scheduleIndexRebuild(reason);
  }

  @VisibleForTesting
  void doClearIndices(@NotNull Predicate<? super ID<?, ?>> filter) {
    try {
      waitUntilIndicesAreInitialized();
    }
    catch (ProcessCanceledException e) {
      // will be rebuilt on re-scan
      return;
    }
    IndexingStamp.flushCaches();

    List<ID<?, ?>> clearedIndexes = new ArrayList<>();
    List<ID<?, ?>> survivedIndexes = new ArrayList<>();
    for (ID<?, ?> indexId : getState().getIndexIDs()) {
      if (filter.test(indexId)) {
        try {
          clearIndex(indexId);
        }
        catch (StorageException e) {
          LOG.info(e);
        }
        catch (Exception e) {
          LOG.error(e);
        }
        clearedIndexes.add(indexId);
      }
      else {
        survivedIndexes.add(indexId);
      }
    }

    LOG.info("indexes cleared: " + clearedIndexes.stream().map(id -> id.getName()).collect(Collectors.joining(", ")) + "\n" +
             "survived indexes: " + survivedIndexes.stream().map(id -> id.getName()).collect(Collectors.joining(", ")));
  }

  @Override
  public void registerProjectFileSets(@NotNull Project project) {
    for (IndexableFilesContributor extension : IndexableFilesContributor.EP_NAME.getExtensions()) {
      Predicate<VirtualFile> contributorsPredicate = extension.getOwnFilePredicate(project);
      registerIndexableSet(new IndexableFileSet() {
        @Override
        public boolean isInSet(@NotNull VirtualFile file) {
          return contributorsPredicate.test(file);
        }
      }, project);
    }
  }

  @Override
  public void removeProjectFileSets(@NotNull Project project) {
    Set<IndexableFileSet> toRemove = new HashSet<>();

    for (Map.Entry<IndexableFileSet, Project> entry : myIndexableSetToProjectMap.entrySet()) {
      if (entry.getValue().equals(project)) {
        toRemove.add(entry.getKey());
      }
    }

    for (IndexableFileSet set : toRemove) {
      myIndexableSets.remove(set);
      myIndexableSetToProjectMap.remove(set);
    }
  }

  boolean processChangedFiles(@NotNull Project project, @NotNull Processor<? super VirtualFile> processor) {
    // can be performance critical, better to use cycle instead of streams
    // avoid missing files when events are processed concurrently
    Iterator<VirtualFile> iterator = Iterators.concat(
      getChangedFilesCollector().getEventMerger().getChangedFiles().iterator(),
      getChangedFilesCollector().getFilesToUpdate().iterator()
    );

    HashSet<VirtualFile> checkedFiles = new HashSet<>();
    Predicate<VirtualFile> filterPredicate = filesToBeIndexedForProjectCondition(project);

    while (iterator.hasNext()) {
      VirtualFile virtualFile = iterator.next();
      if (filterPredicate.test(virtualFile) && !checkedFiles.contains(virtualFile)) {
        checkedFiles.add(virtualFile);
        if (!processor.process(virtualFile)) return false;
      }
    }

    return true;
  }

  RegisteredIndexes getRegisteredIndexes() {
    return myRegisteredIndexes;
  }

  void setUpShutDownTask() {
    myShutDownTask = new MyShutDownTask();
    ShutDownTracker.getInstance().registerShutdownTask(myShutDownTask);
  }

  @ApiStatus.Internal
  public void dumpIndexStatistics() {
    IndexConfiguration state = getRegisteredIndexes().getState();
    String statistics = state.getIndexIDs().stream().map(id -> {
      var indexStats = state.getIndex(id).dumpStatistics();
      if (indexStats == null) return null;
      return "id = " + id + ": " + indexStats;
    }).filter(Objects::nonNull).collect(Collectors.joining(", "));
    if (!statistics.isEmpty()) {
      LOG.info(statistics);
    }
  }

  static class MyShutDownTask implements Runnable {
    @Override
    public void run() {
      FileBasedIndex fileBasedIndex = FileBasedIndex.getInstance();
      if (fileBasedIndex instanceof FileBasedIndexImpl) {
        ((FileBasedIndexImpl)fileBasedIndex).performShutdown(false);
      }
    }
  }

  public static boolean isProjectOrWorkspaceFile(@NotNull VirtualFile file, @Nullable FileType fileType) {
    return ProjectCoreUtil.isProjectOrWorkspaceFile(file, fileType);
  }

  static boolean belongsToScope(VirtualFile file, VirtualFile restrictedTo, GlobalSearchScope filter) {
    if (!(file instanceof VirtualFileWithId) || !file.isValid()) {
      return false;
    }

    return (restrictedTo == null || Comparing.equal(file, restrictedTo)) &&
           (filter == null || restrictedTo != null || filter.accept(file));
  }

  @Override
  public void requestReindex(@NotNull VirtualFile file) {
    requestReindex(file, true);
  }

  @ApiStatus.Internal
  public void requestReindex(@NotNull VirtualFile file, boolean forceRebuildRequest) {
    GistManager.getInstance().invalidateData(file);
    // todo: this is the same vfs event handling sequence that is produces after events of FileContentUtilCore.reparseFiles
    // but it is more costly than current code, see IDEA-192192
    //myChangedFilesCollector.invalidateIndicesRecursively(file, false);
    //myChangedFilesCollector.buildIndicesForFileRecursively(file, false);
    ChangedFilesCollector changedFilesCollector = getChangedFilesCollector();
    changedFilesCollector.invalidateIndicesRecursively(file, true, forceRebuildRequest, changedFilesCollector.getEventMerger());
    if (myRegisteredIndexes.isInitialized()) {
      changedFilesCollector.ensureUpToDateAsync();
    }
  }

  void loadIndexes() {
    LOG.assertTrue(myRegisteredIndexes == null);
    myStorageBufferingHandler.resetState();
    myRegisteredIndexes = new RegisteredIndexes(myFileDocumentManager, this);
  }

  @Override
  public void waitUntilIndicesAreInitialized() {
    if (myRegisteredIndexes == null) {
      // interrupt all calculation while plugin reload
      throw new ServiceNotReadyException();
    }
    myRegisteredIndexes.waitUntilIndicesAreInitialized();
  }

  static <K, V> void registerIndexer(@NotNull final FileBasedIndexExtension<K, V> extension, @NotNull IndexConfiguration state,
                                     @NotNull IndexVersionRegistrationSink registrationStatusSink) throws Exception {
    ID<K, V> name = extension.getName();
    int version = getIndexExtensionVersion(extension);

    final File versionFile = IndexInfrastructure.getVersionFile(name);

    IndexVersion.IndexVersionDiff diff = IndexVersion.versionDiffers(name, version);
    registrationStatusSink.setIndexVersionDiff(name, diff);
    if (diff != IndexVersion.IndexVersionDiff.UP_TO_DATE) {
      final boolean versionFileExisted = versionFile.exists();

      if (extension.hasSnapshotMapping() && versionFileExisted) {
        FileUtil.deleteWithRenaming(IndexInfrastructure.getPersistentIndexRootDir(name));
      }
      File rootDir = IndexInfrastructure.getIndexRootDir(name);
      if (versionFileExisted) FileUtil.deleteWithRenaming(rootDir);
      IndexVersion.rewriteVersion(name, version);

      try {
        if (versionFileExisted) {
          for (FileBasedIndexInfrastructureExtension ex : FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
            ex.onFileBasedIndexVersionChanged(name);
          }
        }
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }

    initIndexStorage(extension, version, state, registrationStatusSink);
  }

  private static <K, V> void initIndexStorage(@NotNull FileBasedIndexExtension<K, V> extension,
                                              int version,
                                              @NotNull IndexConfiguration state,
                                              @NotNull IndexVersionRegistrationSink registrationStatusSink)
    throws Exception {
    final ID<K, V> name = extension.getName();

    final InputFilter inputFilter = extension.getInputFilter();
    final Set<FileType> addedTypes;
    if (inputFilter instanceof FileBasedIndex.FileTypeSpecificInputFilter) {
      addedTypes = new HashSet<>();
      ((FileBasedIndex.FileTypeSpecificInputFilter)inputFilter).registerFileTypesUsedForIndexing(type -> {
        if (type != null) addedTypes.add(type);
      });
    }
    else {
      addedTypes = null;
    }

    boolean contentHashesEnumeratorOk = true;
    if (VfsAwareMapReduceIndex.hasSnapshotMapping(extension)) {
      contentHashesEnumeratorOk = SnapshotHashEnumeratorService.getInstance().initialize();
    }

    int attemptCount = 2;
    for (int attempt = 0; attempt < attemptCount; attempt++) {
      try {
        VfsAwareIndexStorageLayout<K, V> layout = VfsAwareIndexStorageLayout.getLayout(extension, contentHashesEnumeratorOk);
        UpdatableIndex<K, V, FileContent> index = createIndex(extension, layout);

        for (FileBasedIndexInfrastructureExtension infrastructureExtension : FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
          UpdatableIndex<K, V, FileContent> intermediateIndex = infrastructureExtension.combineIndex(extension, index);
          if (intermediateIndex != null) {
            index = intermediateIndex;
          }
        }

        state.registerIndex(name,
                            index,
                            composeInputFilter(inputFilter, file -> !GlobalIndexFilter.isExcludedFromIndexViaFilters(file, name)),
                            version + GlobalIndexFilter.getFiltersVersion(name),
                            addedTypes);
        break;
      }
      catch (Exception e) {
        registrationStatusSink.setIndexVersionDiff(name, new IndexVersion.IndexVersionDiff.CorruptedRebuild(version));
        IndexVersion.rewriteVersion(name, version);

        boolean lastAttempt = attempt == attemptCount - 1;
        if (lastAttempt) {
          throw e;
        }
        else if (ApplicationManager.getApplication().isUnitTestMode()) {
          LOG.error(e);
        }
        else {
          LOG.warn(e);
        }
      }
    }
  }

  @NotNull
  private static <K, V> UpdatableIndex<K, V, FileContent> createIndex(@NotNull FileBasedIndexExtension<K, V> extension,
                                                                      @NotNull VfsAwareIndexStorageLayout<K, V> layout)
    throws StorageException, IOException {
    if (extension instanceof CustomImplementationFileBasedIndexExtension) {
      @SuppressWarnings("unchecked") UpdatableIndex<K, V, FileContent> index =
        ((CustomImplementationFileBasedIndexExtension<K, V>)extension).createIndexImplementation(extension, layout);
      return index;
    }
    else {
      return new TransientFileContentIndex<>(extension, layout, null);
    }
  }

  void performShutdown(boolean keepConnection) {
    RegisteredIndexes registeredIndexes = myRegisteredIndexes;
    if (registeredIndexes == null || !registeredIndexes.performShutdown()) {
      return; // already shut down
    }

    ProgressManager.getInstance().executeNonCancelableSection(() -> {
      registeredIndexes.waitUntilAllIndicesAreInitialized();
    });
    try {
      if (myShutDownTask != null) {
        ShutDownTracker.getInstance().unregisterShutdownTask(myShutDownTask);
      }
      if (myFlushingFuture != null) {
        myFlushingFuture.cancel(false);
        myFlushingFuture = null;
      }
    }
    finally {
      LOG.info("START INDEX SHUTDOWN");
      try {
        PersistentIndicesConfiguration.saveConfiguration();

        for (VirtualFile file : getChangedFilesCollector().getAllPossibleFilesToUpdate()) {
          int fileId = getFileId(file);
          removeDataFromIndicesForFile(fileId, file);
        }

        getChangedFilesCollector().clearFilesToUpdate();

        IndexingStamp.flushCaches();

        IndexConfiguration state = getState();
        for (ID<?, ?> indexId : state.getIndexIDs()) {
          try {
            final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
            assert index != null;
            if (!RebuildStatus.isOk(indexId)) {
              index.clear(); // if the index was scheduled for rebuild, only clean it
            }
            index.dispose();
          }
          catch (Throwable throwable) {
            LOG.info("Problem disposing " + indexId, throwable);
          }
        }

        FileBasedIndexInfrastructureExtension.EP_NAME.extensions().forEach(ex -> ex.shutdown());
        SnapshotHashEnumeratorService.getInstance().close();
        if (!keepConnection) {
          myConnection.disconnect();
        }
      }
      catch (Throwable e) {
        LOG.error("Problems during index shutdown", e);
      }
      finally {
        IndexVersion.clearCachedIndexVersions();
      }
      LOG.info("END INDEX SHUTDOWN");
    }
  }

  private void removeDataFromIndicesForFile(int fileId, VirtualFile file) {
    VirtualFile originalFile = file instanceof DeletedVirtualFileStub ? ((DeletedVirtualFileStub)file).getOriginalFile() : file;
    final List<ID<?, ?>> states = IndexingStamp.getNontrivialFileIndexedStates(fileId);

    if (!states.isEmpty()) {
      ProgressManager.getInstance().executeNonCancelableSection(() -> removeFileDataFromIndices(states, fileId, originalFile));
    }
  }

  private void removeFileDataFromIndices(@NotNull Collection<? extends ID<?, ?>> affectedIndices, int inputId, VirtualFile file) {
    assert ProgressManager.getInstance().isInNonCancelableSection();
    try {
      // document diff can depend on previous value that will be removed
      removeTransientFileDataFromIndices(affectedIndices, inputId, file);

      Throwable unexpectedError = null;
      for (ID<?, ?> indexId : affectedIndices) {
        try {
          updateSingleIndex(indexId, null, inputId, null);
        }
        catch (Throwable e) {
          LOG.info(e);
          if (unexpectedError == null) {
            unexpectedError = e;
          }
        }
      }

      if (unexpectedError != null) {
        LOG.error(unexpectedError);
      }
    }
    finally {
      IndexingStamp.flushCache(inputId);
    }
  }

  private void removeTransientFileDataFromIndices(Collection<? extends ID<?, ?>> indices, int inputId, VirtualFile file) {
    for (ID<?, ?> indexId : indices) {
      final UpdatableIndex<?, ?, FileContent> index = myRegisteredIndexes.getState().getIndex(indexId);
      if (index == null) {
        throw new AssertionError("index '" + indexId.getName() + "' can't be found among registered indexes: " + myRegisteredIndexes.getState().getIndexIDs());
      }
      index.removeTransientDataForFile(inputId);
    }

    Document document = myFileDocumentManager.getCachedDocument(file);
    if (document != null) {
      myLastIndexedDocStamps.clearForDocument(document);
      document.putUserData(ourFileContentKey, null);
    }

    clearUpToDateIndexesForUnsavedOrTransactedDocs();
  }

  private void flushAllIndices(final long modCount) {
    if (HeavyProcessLatch.INSTANCE.isRunning()) {
      return;
    }
    IndexingStamp.flushCaches();
    IndexConfiguration state = getState();
    for (ID<?, ?> indexId : state.getIndexIDs()) {
      if (HeavyProcessLatch.INSTANCE.isRunning() || modCount != myLocalModCount.get()) {
        return; // do not interfere with 'main' jobs
      }
      try {
        final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
        if (index != null) {
          index.flush();
        }
      }
      catch (Throwable e) {
        requestRebuild(indexId, e);
      }
    }

    SnapshotHashEnumeratorService.getInstance().flush();
  }

  private static final ThreadLocal<Integer> myUpToDateCheckState = new ThreadLocal<>();

  public static <T,E extends Throwable> T disableUpToDateCheckIn(@NotNull ThrowableComputable<T, E> runnable) throws E {
    disableUpToDateCheckForCurrentThread();
    try {
      return runnable.compute();
    }
    finally {
      enableUpToDateCheckForCurrentThread();
    }
  }
  private static void disableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    myUpToDateCheckState.set(currentValue == null ? 1 : currentValue.intValue() + 1);
  }

  private static void enableUpToDateCheckForCurrentThread() {
    final Integer currentValue = myUpToDateCheckState.get();
    if (currentValue != null) {
      final int newValue = currentValue.intValue() - 1;
      if (newValue != 0) {
        myUpToDateCheckState.set(newValue);
      }
      else {
        myUpToDateCheckState.remove();
      }
    }
  }

  static boolean isUpToDateCheckEnabled() {
    final Integer value = myUpToDateCheckState.get();
    return value == null || value.intValue() == 0;
  }

  private final ThreadLocal<Boolean> myReentrancyGuard = ThreadLocal.withInitial(() -> Boolean.FALSE);

  @Override
  public <K> boolean ensureUpToDate(@NotNull final ID<K, ?> indexId,
                                    @Nullable Project project,
                                    @Nullable GlobalSearchScope filter,
                                    @Nullable VirtualFile restrictedFile) {
    ProgressManager.checkCanceled();
    getChangedFilesCollector().ensureUpToDate();
    ApplicationManager.getApplication().assertReadAccessAllowed();

    NoAccessDuringPsiEvents.checkCallContext(indexId);

    if (!needsFileContentLoading(indexId)) {
      return true; //indexed eagerly in foreground while building unindexed file list
    }
    if (filter == GlobalSearchScope.EMPTY_SCOPE) {
      return false;
    }
    if (project == null) {
      LOG.warn("Please provide a GlobalSearchScope with specified project. Otherwise it might lead to performance problems!", new Exception());
    }
    if (ActionUtil.isDumbMode(project) && getCurrentDumbModeAccessType_NoDumbChecks() == null) {
      handleDumbMode(project);
    }

    if (myReentrancyGuard.get().booleanValue()) {
      //assert false : "ensureUpToDate() is not reentrant!";
      return true;
    }
    myReentrancyGuard.set(Boolean.TRUE);

    try {
      if (isUpToDateCheckEnabled()) {
        try {
          if (!RebuildStatus.isOk(indexId)) {
            if (getCurrentDumbModeAccessType_NoDumbChecks() == null) {
              throw new ServiceNotReadyException();
            }
            return false;
          }
          if (!ActionUtil.isDumbMode(project) || getCurrentDumbModeAccessType_NoDumbChecks() == null) {
            forceUpdate(project, filter, restrictedFile);
          }
          if (!areUnsavedDocumentsIndexed(indexId)) { // todo: check scope ?
            indexUnsavedDocuments(indexId, project, filter, restrictedFile);
          }
        }
        catch (RuntimeException e) {
          final Throwable cause = e.getCause();
          if (cause instanceof StorageException || cause instanceof IOException) {
            scheduleRebuild(indexId, e);
          }
          else {
            throw e;
          }
        }
      }
    }
    finally {
      myReentrancyGuard.set(Boolean.FALSE);
    }
    return true;
  }

  private boolean areUnsavedDocumentsIndexed(@NotNull ID<?, ?> indexId) {
    return myUpToDateIndicesForUnsavedOrTransactedDocuments.contains(indexId);
  }

  private static void handleDumbMode(@Nullable Project project) throws IndexNotReadyException {
    ProgressManager.checkCanceled();
    throw IndexNotReadyException.create(project == null ? null : DumbServiceImpl.getInstance(project).getDumbModeStartTrace());
  }

  private static final Key<SoftReference<ProjectIndexableFilesFilter>> ourProjectFilesSetKey = Key.create("projectFiles");

  @TestOnly
  public void cleanupForNextTest() {
    getChangedFilesCollector().ensureUpToDate();

    myTransactionMap = SmartFMap.emptyMap();
    IndexConfiguration state = getState();
    for (ID<?, ?> indexId : state.getIndexIDs()) {
      final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
      assert index != null;
      index.cleanupForNextTest();
    }
  }

  @ApiStatus.Internal
  public ChangedFilesCollector getChangedFilesCollector() {
    return myChangedFilesCollector.getValue();
  }

  void incrementFilesModCount() {
    myFilesModCount.incrementAndGet();
  }

  void filesUpdateStarted(Project project) {
    getChangedFilesCollector().ensureUpToDate();
    myProjectsBeingUpdated.add(project);
    incrementFilesModCount();
  }

  void filesUpdateFinished(@NotNull Project project) {
    myProjectsBeingUpdated.remove(project);
    incrementFilesModCount();
  }

  private final Lock myCalcIndexableFilesLock = new ReentrantLock();

  @Override
  @Nullable
  public ProjectIndexableFilesFilter projectIndexableFiles(@Nullable Project project) {
    if (project == null || project.isDefault() || getChangedFilesCollector().isUpdateInProgress()) return null;
    if (myProjectsBeingUpdated.contains(project) || !UnindexedFilesUpdater.isProjectContentFullyScanned(project)) return null;

    SoftReference<ProjectIndexableFilesFilter> reference = project.getUserData(ourProjectFilesSetKey);
    ProjectIndexableFilesFilter data = com.intellij.reference.SoftReference.dereference(reference);
    int currentFileModCount = myFilesModCount.get();
    if (data != null && data.getModificationCount() == currentFileModCount) return data;

    if (myCalcIndexableFilesLock.tryLock()) { // make best effort for calculating filter
      try {
        reference = project.getUserData(ourProjectFilesSetKey);
        data = com.intellij.reference.SoftReference.dereference(reference);
        if (data != null) {
          if (data.getModificationCount() == currentFileModCount) {
            return data;
          }
        }
        else if (!isUpToDateCheckEnabled()) {
          return null;
        }

        long start = System.currentTimeMillis();

        IntList fileSet = new IntArrayList();
        iterateIndexableFiles(fileOrDir -> {
          if (fileOrDir instanceof VirtualFileWithId) {
            fileSet.add(((VirtualFileWithId)fileOrDir).getId());
          }
          return true;
        }, project, null);
        ProjectIndexableFilesFilter filter = new ProjectIndexableFilesFilter(fileSet, currentFileModCount);
        project.putUserData(ourProjectFilesSetKey, new SoftReference<>(filter));

        long finish = System.currentTimeMillis();
        LOG.debug(fileSet.size() + " files iterated in " + (finish - start) + " ms");

        return filter;
      }
      finally {
        myCalcIndexableFilesLock.unlock();
      }
    }
    return null; // ok, no filtering
  }

  @Nullable
  public static Throwable getCauseToRebuildIndex(@NotNull RuntimeException e) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      // avoid rebuilding index in tests since we do it synchronously in requestRebuild and we can have readAction at hand
      return null;
    }
    if (e instanceof ProcessCanceledException) {
      return null;
    }
    if (e instanceof MapReduceIndex.MapInputException) {
      // If exception has happened on input mapping (DataIndexer.map),
      // it is handled as the indexer exception and must not lead to index rebuild.
      return null;
    }
    if (e instanceof IndexOutOfBoundsException) return e; // something wrong with direct byte buffer
    Throwable cause = e.getCause();
    if (cause instanceof StorageException
        || cause instanceof IOException
        || cause instanceof IllegalArgumentException
    ) {
      return cause;
    }
    return null;
  }

  private static void scheduleIndexRebuild(String reason) {
    LOG.info("schedule index re-scanning, reason: " + reason);
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      DumbService.getInstance(project).queueTask(new UnindexedFilesUpdater(project));
    }
  }

  void clearIndicesIfNecessary() {
    waitUntilIndicesAreInitialized();
    for (ID<?, ?> indexId : getState().getIndexIDs()) {
      try {
        RebuildStatus.clearIndexIfNecessary(indexId, getIndex(indexId)::clear);
      }
      catch (StorageException e) {
        requestRebuild(indexId);
        LOG.error(e);
      }
    }
  }

  void clearIndex(@NotNull final ID<?, ?> indexId) throws StorageException {
    advanceIndexVersion(indexId);

    final UpdatableIndex<?, ?, FileContent> index = myRegisteredIndexes.getState().getIndex(indexId);
    assert index != null : "Index with key " + indexId + " not found or not registered properly";
    index.clear();
  }

  private void advanceIndexVersion(ID<?, ?> indexId) {
    try {
      IndexVersion.rewriteVersion(indexId, myRegisteredIndexes.getState().getIndexVersion(indexId));
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  @NotNull
  private Set<Document> getUnsavedDocuments() {
    Document[] documents = myFileDocumentManager.getUnsavedDocuments();
    if (documents.length == 0) return Collections.emptySet();
    if (documents.length == 1) return Collections.singleton(documents[0]);
    return ContainerUtil.set(documents);
  }

  @NotNull
  private Set<Document> getTransactedDocuments() {
    return myTransactionMap.keySet();
  }

  private void indexUnsavedDocuments(@NotNull final ID<?, ?> indexId,
                                     @Nullable Project project,
                                     final GlobalSearchScope filter,
                                     final VirtualFile restrictedFile) {
    if (myUpToDateIndicesForUnsavedOrTransactedDocuments.contains(indexId)) {
      return; // no need to index unsaved docs        // todo: check scope ?
    }

    Collection<Document> documents = getUnsavedDocuments();
    Set<Document> transactedDocuments = getTransactedDocuments();
    if (documents.isEmpty()) {
      documents = transactedDocuments;
    }
    else if (!transactedDocuments.isEmpty()) {
      documents = new HashSet<>(documents);
      documents.addAll(transactedDocuments);
    }
    Document[] uncommittedDocuments = project != null ? PsiDocumentManager.getInstance(project).getUncommittedDocuments() : Document.EMPTY_ARRAY;
    if (uncommittedDocuments.length > 0) {
      List<Document> uncommittedDocumentsCollection = Arrays.asList(uncommittedDocuments);
      if (documents.isEmpty()) documents = uncommittedDocumentsCollection;
      else {
        if (!(documents instanceof HashSet)) documents = new HashSet<>(documents);

        documents.addAll(uncommittedDocumentsCollection);
      }
    }

    if (!documents.isEmpty()) {
      Collection<Document> documentsToProcessForProject = ContainerUtil.filter(documents,
                                                                               document -> belongsToScope(myFileDocumentManager.getFile(document), restrictedFile, filter));

      if (!documentsToProcessForProject.isEmpty()) {
        UpdateTask<Document> task = myRegisteredIndexes.getUnsavedDataUpdateTask(indexId);
        assert task != null : "Task for unsaved data indexing was not initialized for index " + indexId;

        if(myStorageBufferingHandler.runUpdate(true, () -> task.processAll(documentsToProcessForProject, project)) &&
           documentsToProcessForProject.size() == documents.size() &&
           !hasActiveTransactions()
          ) {
          ProgressManager.checkCanceled();
          myUpToDateIndicesForUnsavedOrTransactedDocuments.add(indexId);
        }
      }
    }
  }

  private boolean hasActiveTransactions() {
    return !myTransactionMap.isEmpty();
  }


  private static final Key<WeakReference<Pair<FileContentImpl, Long>>> ourFileContentKey = Key.create("unsaved.document.index.content");

  // returns false if doc was not indexed because it is already up to date
  // return true if document was indexed
  // caller is responsible to ensure no concurrent same document processing
  void indexUnsavedDocument(@NotNull final Document document,
                            @NotNull final ID<?, ?> requestedIndexId,
                            @NotNull Project project,
                            @NotNull final VirtualFile vFile) {
    myStorageBufferingHandler.assertTransientMode();

    final PsiFile dominantContentFile = findLatestKnownPsiForUncomittedDocument(document, project);

    final DocumentContent content;
    // TODO seems we should choose the source with highest stamp!
    if (dominantContentFile != null && dominantContentFile.getViewProvider().getModificationStamp() != document.getModificationStamp()) {
      content = new PsiContent(document, dominantContentFile);
    }
    else {
      content = new AuthenticContent(document);
    }

    final long currentDocStamp = PsiDocumentManager.getInstance(project).getLastCommittedStamp(document);

    final long previousDocStamp = myLastIndexedDocStamps.get(document, requestedIndexId);
    if (previousDocStamp == currentDocStamp) return;

    final CharSequence contentText = content.getText();
    FileTypeManagerEx.getInstanceEx().freezeFileTypeTemporarilyIn(vFile, () -> {
      IndexedFileImpl indexedFile = new IndexedFileImpl(vFile, project);
      if (getAffectedIndexCandidates(indexedFile).contains(requestedIndexId) &&
          acceptsInput(requestedIndexId, indexedFile)) {
        final int inputId = getFileId(vFile);

        if (!isTooLarge(vFile, (long)contentText.length())) {
          // Reasonably attempt to use same file content when calculating indices as we can evaluate them several at once and store in file content
          WeakReference<Pair<FileContentImpl, Long>> previousContentAndStampRef = document.getUserData(ourFileContentKey);
          Pair<FileContentImpl, Long> previousContentAndStamp = com.intellij.reference.SoftReference.dereference(previousContentAndStampRef);
          final FileContentImpl newFc;
          if (previousContentAndStamp != null && currentDocStamp == previousContentAndStamp.getSecond()) {
            newFc = previousContentAndStamp.getFirst();
          }
          else {
            newFc = (FileContentImpl)FileContentImpl.createByText(vFile, contentText);
            document.putUserData(ourFileContentKey, new WeakReference<>(Pair.create(newFc, currentDocStamp)));
          }

          initFileContent(newFc, project, dominantContentFile);
          newFc.ensureThreadSafeLighterAST();

          if (content instanceof AuthenticContent) {
            newFc.putUserData(PlatformIdTableBuilding.EDITOR_HIGHLIGHTER,
                              EditorHighlighterCache.getEditorHighlighterForCachesBuilding(document));
          }

          markFileIndexed(vFile);
          try {
            getIndex(requestedIndexId).mapInputAndPrepareUpdate(inputId, newFc).compute();
          }
          finally {
            unmarkBeingIndexed();
            cleanFileContent(newFc, dominantContentFile);
          }
        }
        else { // effectively wipe the data from the indices
          getIndex(requestedIndexId).mapInputAndPrepareUpdate(inputId, null).compute();
        }
      }

      long previousState = myLastIndexedDocStamps.set(document, requestedIndexId, currentDocStamp);
      assert previousState == previousDocStamp;
    });
  }

  @NotNull
  @Override
  public <K, V> Map<K, V> getFileData(@NotNull ID<K, V> id, @NotNull VirtualFile virtualFile, @NotNull Project project) {
    if (ModelBranch.getFileBranch(virtualFile) != null) {
      return getInMemoryData(id, virtualFile, project);
    }

    return super.getFileData(id, virtualFile, project);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @NotNull
  private <K, V> Map<K, V> getInMemoryData(@NotNull ID<K, V> id, @NotNull VirtualFile virtualFile, @NotNull Project project) {
    PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
    if (psiFile != null) {
      Map<ID, Map> indexValues = CachedValuesManager.getCachedValue(psiFile, () -> {
        try {
          FileContentImpl fc = psiFile instanceof PsiBinaryFile ? (FileContentImpl)FileContentImpl.createByFile(virtualFile)
                                                                : (FileContentImpl)FileContentImpl.createByText(virtualFile, psiFile.getViewProvider().getContents());
          initFileContent(fc, project, psiFile);
          Map<ID, Map> result = FactoryMap.create(key -> getIndex(key).getExtension().getIndexer().map(fc));
          return CachedValueProvider.Result.createSingleDependency(result, psiFile);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      return indexValues.get(id);
    }
    return Collections.emptyMap();
  }


  private final StorageBufferingHandler myStorageBufferingHandler = new StorageBufferingHandler() {
    @NotNull
    @Override
    protected Stream<UpdatableIndex<?, ?, ?>> getIndexes() {
      IndexConfiguration state = getState();
      return state.getIndexIDs().stream().map(id -> state.getIndex(id));
    }
  };

  @ApiStatus.Internal
  public void runCleanupAction(@NotNull Runnable cleanupAction) {
    Computable<Boolean> updateComputable = () -> {
      cleanupAction.run();
      return true;
    };
    myStorageBufferingHandler.runUpdate(false, updateComputable);
    myStorageBufferingHandler.runUpdate(true, updateComputable);
  }

  void cleanupMemoryStorage(boolean skipContentDependentIndexes) {
    myLastIndexedDocStamps.clear();
    if (myRegisteredIndexes == null) {
      // unsaved doc is dropped while plugin load/unload-ing
      return;
    }
    IndexConfiguration state = myRegisteredIndexes.getState();
    if (state == null) {
      // avoid waiting for end of indices initialization (IDEA-173382)
      // in memory content will appear on indexing (in read action) and here is event dispatch (write context)
      return;
    }
    for (ID<?, ?> indexId : state.getIndexIDs()) {
      if (skipContentDependentIndexes && myRegisteredIndexes.isContentDependentIndex(indexId)) continue;
      final UpdatableIndex<?, ?, FileContent> index = state.getIndex(indexId);
      assert index != null;
      index.cleanupMemoryStorage();
    }
  }

  @Override
  public void requestRebuild(@NotNull final ID<?, ?> indexId, final @NotNull Throwable throwable) {
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) {
      IndexDataInitializer.submitGenesisTask(() -> {
        waitUntilIndicesAreInitialized(); // should be always true here since the genesis pool is sequential
        doRequestRebuild(indexId, throwable);
        return null;
      });
    }
    else {
      doRequestRebuild(indexId, throwable);
    }
  }

  private void doRequestRebuild(@NotNull ID<?, ?> indexId, Throwable throwable) {
    cleanupProcessedFlag();
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) reportUnexpectedAsyncInitState();

    if (RebuildStatus.requestRebuild(indexId)) {
      String message = "Rebuild requested for index " + indexId;
      Application app = ApplicationManager.getApplication();
      if (app.isUnitTestMode() && app.isReadAccessAllowed() && !app.isDispatchThread()) {
        // shouldn't happen in tests in general; so fail early with the exception that caused index to be rebuilt.
        // otherwise reindexing will fail anyway later, but with a much more cryptic assertion
        LOG.error(message, throwable);
      }
      else {
        LOG.info(message, throwable);
      }

      cleanupProcessedFlag();

      if (!myRegisteredIndexes.isInitialized()) return;
      advanceIndexVersion(indexId);

      Runnable rebuildRunnable = () -> scheduleIndexRebuild("checkRebuild");

      if (myIsUnitTestMode) {
        rebuildRunnable.run();
      }
      else {
        // we do invoke later since we can have read lock acquired
        AppUIExecutor.onWriteThread().later().expireWith(app).submit(rebuildRunnable);
      }
    }
  }

  private static void reportUnexpectedAsyncInitState() {
    LOG.error("Unexpected async indices initialization problem");
  }

  @NotNull
  @Override
  public <K, V> UpdatableIndex<K, V, FileContent> getIndex(ID<K, V> indexId) {
    UpdatableIndex<K, V, FileContent> index = getState().getIndex(indexId);
    if (index == null) {
      throw new IllegalStateException("Index is not created for `" + indexId.getName() + "`");
    }
    return index;
  }

  private InputFilter getInputFilter(@NotNull ID<?, ?> indexId) {
    if (!myRegisteredIndexes.isInitialized()) {
      // 1. early vfs event that needs invalidation
      // 2. pushers that do synchronous indexing for contentless indices
      waitUntilIndicesAreInitialized();
    }

    return getState().getInputFilter(indexId);
  }

  @NotNull
  Collection<VirtualFile> getFilesToUpdate(final Project project) {
    return ContainerUtil.filter(getChangedFilesCollector().getAllFilesToUpdate(), filesToBeIndexedForProjectCondition(project)::test);
  }

  @NotNull
  private Predicate<VirtualFile> filesToBeIndexedForProjectCondition(Project project) {
    return virtualFile -> {
        if (!virtualFile.isValid()) {
          return true;
        }

        for (IndexableFileSet set : myIndexableSets) {
          final Project proj = myIndexableSetToProjectMap.get(set);
          if (proj != null && !proj.equals(project)) {
            continue; // skip this set as associated with a different project
          }
          if (ReadAction.compute(() -> set.isInSet(virtualFile))) {
            return true;
          }
        }
        return false;
      };
  }

  public boolean isFileUpToDate(VirtualFile file) {
    return file instanceof VirtualFileWithId && !getChangedFilesCollector().isScheduledForUpdate(file);
  }

  // caller is responsible to ensure no concurrent same document processing
  private void processRefreshedFile(@Nullable Project project, @NotNull final CachedFileContent fileContent) {
    // ProcessCanceledException will cause re-adding the file to processing list
    final VirtualFile file = fileContent.getVirtualFile();
    if (getChangedFilesCollector().isScheduledForUpdate(file)) {
      indexFileContent(project, fileContent);
    }
  }

  @ApiStatus.Internal
  @NotNull
  public FileIndexingStatistics indexFileContent(@Nullable Project project, @NotNull CachedFileContent content) {
    ProgressManager.checkCanceled();
    VirtualFile file = content.getVirtualFile();
    final int fileId = getFileId(file);

    FileIndexingResult indexingResult;
    try {
      boolean isValid = file.isValid();
      // if file was scheduled for update due to vfs events then it is present in myFilesToUpdate
      // in this case we consider that current indexing (out of roots backed CacheUpdater) will cover its content
      if (file.isValid() && content.getTimeStamp() != file.getTimeStamp()) {
        content = new CachedFileContent(file);
      }

      boolean isIndexesDeleted;
      if (!isValid || isTooLarge(file)) {
        isIndexesDeleted = true;
        ProgressManager.checkCanceled();
        removeDataFromIndicesForFile(fileId, file);
        indexingResult = new FileIndexingResult(true,
                                                Collections.emptyMap(),
                                                Collections.emptyMap(),
                                                file.getFileType(),
                                                Collections.emptySet(),
                                                false);
      }
      else {
        isIndexesDeleted = false;
        indexingResult = doIndexFileContent(project, content);
      }

      if (indexingResult.setIndexedStatus && file instanceof VirtualFileSystemEntry) {
        ((VirtualFileSystemEntry)file).setFileIndexed(true);
      }
      if (VfsEventsMerger.LOG != null) {
        VfsEventsMerger.LOG.info("File " + file +
                                 " indexes have been updated for indexes " + indexingResult.updateTimesPerIndexer.keySet() +
                                 " and deleted for " + indexingResult.deletionTimesPerIndexer.keySet() +
                                 ". Indexes was wiped = " + isIndexesDeleted +
                                 "; is file valid = " + isValid);
      }
      getChangedFilesCollector().removeFileIdFromFilesScheduledForUpdate(fileId);
      // Indexing time takes only input data mapping time into account.
      long indexingTime =
        indexingResult.updateTimesPerIndexer.values().stream().mapToLong(e -> e).sum() +
        indexingResult.deletionTimesPerIndexer.values().stream().mapToLong(e -> e).sum();
      return new FileIndexingStatistics(indexingTime,
                                        indexingResult.fileType,
                                        indexingResult.indexesProvidedByExtensions,
                                        indexingResult.wasFullyIndexedByExtensions,
                                        ContainerUtil.union(indexingResult.updateTimesPerIndexer, indexingResult.deletionTimesPerIndexer));
    }
    finally {
      IndexingStamp.flushCache(fileId);
    }
  }

  private static final class FileIndexingResult {
    public final boolean setIndexedStatus;
    public final Map<ID<?, ?>, Long> updateTimesPerIndexer;
    public final Map<ID<?, ?>, Long> deletionTimesPerIndexer;
    public final FileType fileType;
    public final Set<ID<?, ?>> indexesProvidedByExtensions;
    public final boolean wasFullyIndexedByExtensions;

    private FileIndexingResult(boolean setIndexedStatus,
                               @NotNull Map<ID<?, ?>, Long> updateTimesPerIndexer,
                               @NotNull Map<ID<?, ?>, Long> deletionTimesPerIndexer,
                               @NotNull FileType type,
                               @NotNull Set<ID<?, ?>> indexesProvidedByExtensions,
                               boolean wasFullyIndexedByExtensions) {
      this.setIndexedStatus = setIndexedStatus;
      this.updateTimesPerIndexer = updateTimesPerIndexer;
      this.deletionTimesPerIndexer = deletionTimesPerIndexer;
      this.fileType = type;
      this.indexesProvidedByExtensions = indexesProvidedByExtensions;
      this.wasFullyIndexedByExtensions = wasFullyIndexedByExtensions;
    }
  }

  private static final class SingleIndexUpdateStats {
    public final long mapInputTime;
    public final boolean indexWasProvidedByExtension;

    private SingleIndexUpdateStats(long mapInputTime, boolean indexWasProvidedByExtension) {
      this.mapInputTime = mapInputTime;
      this.indexWasProvidedByExtension = indexWasProvidedByExtension;
    }
  }

  @NotNull
  private FileBasedIndexImpl.FileIndexingResult doIndexFileContent(@Nullable Project project, @NotNull CachedFileContent content) {
    ProgressManager.checkCanceled();
    final VirtualFile file = content.getVirtualFile();
    Ref<Boolean> setIndexedStatus = Ref.create(Boolean.TRUE);
    Map<ID<?, ?>, Long> perIndexerUpdateTimes = new HashMap<>();
    Map<ID<?, ?>, Long> perIndexerDeletionTimes = new HashMap<>();
    Set<ID<?,?>> indexesProvidedByExtensions = new HashSet<>();
    Ref<Boolean> wasFullyIndexedByInfrastructureExtensions = Ref.create(true);
    Ref<FileType> fileTypeRef = Ref.create();
    Project guessedProject = project != null ? project : ProjectUtil.guessProjectForFile(file);
    IndexedFileImpl indexedFile = new IndexedFileImpl(file, guessedProject);

    FileTypeManagerEx.getInstanceEx().freezeFileTypeTemporarilyIn(file, () -> {
      ProgressManager.checkCanceled();

      FileContentImpl fc = null;
      PsiFile psiFile = null;

      int inputId = getFileId(file);
      Set<ID<?, ?>> currentIndexedStates = new HashSet<>(IndexingStamp.getNontrivialFileIndexedStates(inputId));
      List<ID<?, ?>> affectedIndexCandidates = getAffectedIndexCandidates(indexedFile);
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, size = affectedIndexCandidates.size(); i < size; ++i) {
        try {
          ProgressManager.checkCanceled();

          if (fc == null) {
            fc = (FileContentImpl)FileContentImpl.createByContent(file, () -> getBytesOrNull(content));
            fc.setSubstituteFileType(indexedFile.getFileType());
            ProgressManager.checkCanceled();

            psiFile = content.getUserData(IndexingDataKeys.PSI_FILE);
            initFileContent(fc, guessedProject, psiFile);

            fileTypeRef.set(fc.getFileType());

            ProgressManager.checkCanceled();
          }

          final ID<?, ?> indexId = affectedIndexCandidates.get(i);
          if (acceptsInput(indexId, fc) && getIndexingState(fc, indexId).updateRequired()) {
            ProgressManager.checkCanceled();
            SingleIndexUpdateStats updateStats = updateSingleIndex(indexId, file, inputId, fc);
            if (updateStats == null) {
              setIndexedStatus.set(Boolean.FALSE);
            }
            else {
              perIndexerUpdateTimes.put(indexId, updateStats.mapInputTime);
              if (updateStats.indexWasProvidedByExtension) {
                indexesProvidedByExtensions.add(indexId);
              }
              else {
                wasFullyIndexedByInfrastructureExtensions.set(false);
              }
            }
            currentIndexedStates.remove(indexId);
          }
        }
        catch (ProcessCanceledException e) {
          cleanFileContent(fc, psiFile);
          throw e;
        }
      }

      if (psiFile != null) {
        psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
      }

      boolean shouldClearAllIndexedStates = fc == null;
      for (ID<?, ?> indexId : currentIndexedStates) {
        ProgressManager.checkCanceled();
        if (shouldClearAllIndexedStates || getIndex(indexId).getIndexingStateForFile(inputId, fc).updateRequired()) {
          ProgressManager.checkCanceled();
          SingleIndexUpdateStats updateStats = updateSingleIndex(indexId, file, inputId, null);
          if (updateStats == null) {
            setIndexedStatus.set(Boolean.FALSE);
          }
          else {
            perIndexerDeletionTimes.put(indexId, updateStats.mapInputTime);
          }
        }
      }

      fileTypeRef.set(fc != null ? fc.getFileType() : file.getFileType());
    });

    file.putUserData(IndexingDataKeys.REBUILD_REQUESTED, null);
    return new FileIndexingResult(setIndexedStatus.get(),
                                  perIndexerUpdateTimes,
                                  perIndexerDeletionTimes,
                                  fileTypeRef.get(),
                                  indexesProvidedByExtensions,
                                  wasFullyIndexedByInfrastructureExtensions.get());
  }

  private static byte @NotNull[] getBytesOrNull(@NotNull CachedFileContent content) {
    try {
      return content.getBytes();
    }
    catch (IOException e) {
      return ArrayUtilRt.EMPTY_BYTE_ARRAY;
    }
  }

  @NotNull
  List<ID<?, ?>> getAffectedIndexCandidates(@NotNull IndexedFile indexedFile) {
    if (indexedFile.getFile().isDirectory()) {
      return isProjectOrWorkspaceFile(indexedFile.getFile(), null) ? Collections.emptyList() : myRegisteredIndexes.getIndicesForDirectories();
    }
    FileType fileType = indexedFile.getFileType();
    if (fileType instanceof SubstitutedFileType) {
      fileType = ((SubstitutedFileType)fileType).getFileType();
    }
    if(isProjectOrWorkspaceFile(indexedFile.getFile(), fileType)) return Collections.emptyList();

    return getState().getFileTypesForIndex(fileType);
  }

  private static void cleanFileContent(FileContentImpl fc, PsiFile psiFile) {
    if (fc == null) return;
    if (psiFile != null) psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
    fc.putUserData(IndexingDataKeys.PSI_FILE, null);
  }

  private static void initFileContent(@NotNull FileContentImpl fc, Project project, PsiFile psiFile) {
    if (psiFile != null) {
      psiFile.putUserData(PsiFileImpl.BUILDING_STUB, true);
      fc.putUserData(IndexingDataKeys.PSI_FILE, psiFile);
    }

    fc.setProject(project);
  }

  @Nullable("null in case index update is not necessary or the update has failed")
  SingleIndexUpdateStats updateSingleIndex(@NotNull ID<?, ?> indexId, @Nullable VirtualFile file, int inputId, @Nullable FileContent currentFC) {
    if (!myRegisteredIndexes.isExtensionsDataLoaded()) reportUnexpectedAsyncInitState();
    if (!RebuildStatus.isOk(indexId) && !myIsUnitTestMode) {
      return null; // the index is scheduled for rebuild, no need to update
    }
    myLocalModCount.incrementAndGet();

    UpdatableIndex<?, ?, FileContent> index = getIndex(indexId);

    if (currentFC instanceof FileContentImpl && FileBasedIndex.ourSnapshotMappingsEnabled) {
      // Optimization: initialize indexed file hash eagerly. The hash is calculated by raw content bytes.
      // If we pass the currentFC to an indexer that calls "FileContentImpl.getContentAsText",
      // the raw bytes will be converted to text and assigned to null.
      // Then, to compute the hash, the reverse conversion will be necessary.
      // To avoid this extra conversion, let's initialize the hash eagerly.
      IndexedHashesSupport.getOrInitIndexedHash((FileContentImpl)currentFC);
    }

    markFileIndexed(file);
    try {
      Computable<Boolean> storageUpdate;
      long mapInputTime = System.nanoTime();
      try {
        storageUpdate = index.mapInputAndPrepareUpdate(inputId, currentFC);
      } catch (MapReduceIndex.MapInputException e) {
        LOG.error(e);
        if (currentFC != null) {
          setIndexedState(index, currentFC, inputId, false);
        }
        return null;
      } finally {
        mapInputTime = System.nanoTime() - mapInputTime;
      }

      boolean indexWasProvided = storageUpdate instanceof IndexInfrastructureExtensionUpdateComputation &&
                                 ((IndexInfrastructureExtensionUpdateComputation)storageUpdate).isIndexProvided();

      if (myStorageBufferingHandler.runUpdate(false, () -> {
        myStorageBufferingHandler.assertOnTheDiskMode();
        return storageUpdate.compute();
      })) {
        ConcurrencyUtil.withLock(myReadLock, () -> {
          if (currentFC != null) {
            if (!isMock(currentFC.getFile())) {
              setIndexedState(index, currentFC, inputId, indexWasProvided);
            }
          }
          else {
            index.setUnindexedStateForFile(inputId);
          }
        });
      }

      return new SingleIndexUpdateStats(mapInputTime, indexWasProvided);
    }
    catch (RuntimeException exception) {
      Throwable causeToRebuildIndex = getCauseToRebuildIndex(exception);
      if (causeToRebuildIndex != null) {
        requestRebuild(indexId, exception);
        return null;
      }
      throw exception;
    }
    finally {
      unmarkBeingIndexed();
    }
  }

  static void setIndexedState(UpdatableIndex<?, ?, FileContent> index,
                              @NotNull IndexedFile currentFC,
                              int inputId,
                              boolean indexWasProvided) {
    if (index instanceof FileBasedIndexInfrastructureExtensionUpdatableIndex) {
      ((FileBasedIndexInfrastructureExtensionUpdatableIndex<?, ?, ?>)index)
        .setIndexedStateForFile(inputId, currentFC, indexWasProvided);
    }
    else {
      index.setIndexedStateForFile(inputId, currentFC);
    }
  }

  private static void markFileIndexed(@Nullable VirtualFile file) {
    if (ourIndexedFile.get() != null || ourFileToBeIndexed.get() != null) {
      throw new AssertionError("Reentrant indexing");
    }
    ourIndexedFile.set(file);
  }

  private static void unmarkBeingIndexed() {
    ourIndexedFile.remove();
  }

  @Override
  public VirtualFile getFileBeingCurrentlyIndexed() {
    return ourIndexedFile.get();
  }

  private class VirtualFileUpdateTask extends UpdateTask<VirtualFile> {
    @Override
    void doProcess(VirtualFile item, Project project) {
      processRefreshedFile(project, new CachedFileContent(item));
    }
  }

  private final VirtualFileUpdateTask myForceUpdateTask = new VirtualFileUpdateTask();
  private volatile long myLastOtherProjectInclusionStamp;

  private void forceUpdate(@Nullable Project project, @Nullable final GlobalSearchScope filter, @Nullable final VirtualFile restrictedTo) {
    Collection<VirtualFile> allFilesToUpdate = getChangedFilesCollector().getAllFilesToUpdate();

    if (!allFilesToUpdate.isEmpty()) {
      boolean includeFilesFromOtherProjects = restrictedTo == null && System.currentTimeMillis() - myLastOtherProjectInclusionStamp > 100;
      List<VirtualFile> virtualFilesToBeUpdatedForProject = ContainerUtil.filter(
        allFilesToUpdate,
        new ProjectFilesCondition(projectIndexableFiles(project), filter, restrictedTo,
                                  includeFilesFromOtherProjects)
      );

      if (!virtualFilesToBeUpdatedForProject.isEmpty()) {
        myForceUpdateTask.processAll(virtualFilesToBeUpdatedForProject, project);
      }
      if (includeFilesFromOtherProjects) {
        myLastOtherProjectInclusionStamp = System.currentTimeMillis();
      }
    }
  }

  public boolean needsFileContentLoading(@NotNull ID<?, ?> indexId) {
    return myRegisteredIndexes.isContentDependentIndex(indexId);
  }

  @Nullable IndexableFileSet getIndexableSetForFile(VirtualFile file) {
    for (IndexableFileSet set : myIndexableSets) {
      if (set.isInSet(file)) {
        return set;
      }
    }
    return null;
  }

  @NotNull
  public List<IndexableFileSet> getIndexableSets() {
    return myIndexableSets;
  }

  public boolean containsIndexableSet(@NotNull IndexableFileSet set, @NotNull Project project) {
    return project.equals(myIndexableSetToProjectMap.get(set));
  }

  @ApiStatus.Internal
  public void dropNontrivialIndexedStates(int inputId) {
    for (ID<?, ?> id : IndexingStamp.getNontrivialFileIndexedStates(inputId)) {
      dropNontrivialIndexedStates(inputId, id);
    }
  }

  @ApiStatus.Internal
  public void dropNontrivialIndexedStates(int inputId, ID<?, ?> indexId) {
    UpdatableIndex<?, ?, FileContent> index = getIndex(indexId);
    index.invalidateIndexedStateForFile(inputId);
  }

  void doTransientStateChangeForFile(int fileId, @NotNull VirtualFile file) {
    waitUntilIndicesAreInitialized();
    clearUpToDateIndexesForUnsavedOrTransactedDocs();

    Document document = myFileDocumentManager.getCachedDocument(file);
    if (document != null && myFileDocumentManager.isDocumentUnsaved(document)) {   // will be reindexed in indexUnsavedDocuments
      myLastIndexedDocStamps.clearForDocument(document); // Q: non psi indices
      document.putUserData(ourFileContentKey, null);

      return;
    }

    Collection<ID<?, ?>> contentDependentIndexes = ContainerUtil.intersection(IndexingStamp.getNontrivialFileIndexedStates(fileId),
                                                                              myRegisteredIndexes.getRequiringContentIndices());
    removeTransientFileDataFromIndices(contentDependentIndexes, fileId, file);
    for (ID<?, ?> candidate : contentDependentIndexes) {
      getIndex(candidate).invalidateIndexedStateForFile(fileId);
    }
    IndexingStamp.flushCache(fileId);

    getChangedFilesCollector().scheduleForUpdate(file);
  }

  void doInvalidateIndicesForFile(int fileId, @NotNull VirtualFile file, boolean contentChanged) {
    waitUntilIndicesAreInitialized();
    cleanProcessedFlag(file);

    List<ID<?, ?>> nontrivialFileIndexedStates = IndexingStamp.getNontrivialFileIndexedStates(fileId);
    Collection<ID<?, ?>> fileIndexedStatesToUpdate = ContainerUtil.intersection(nontrivialFileIndexedStates, myRegisteredIndexes.getRequiringContentIndices());

    // transient index value can depend on disk value because former is diff to latter
    // it doesn't matter content hanged or not: indices might depend on file name too
    removeTransientFileDataFromIndices(nontrivialFileIndexedStates, fileId, file);

    if (contentChanged) {
      // only mark the file as outdated, reindex will be done lazily
      if (!fileIndexedStatesToUpdate.isEmpty()) {
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0, size = nontrivialFileIndexedStates.size(); i < size; ++i) {
          final ID<?, ?> indexId = nontrivialFileIndexedStates.get(i);
          if (needsFileContentLoading(indexId)) {
            getIndex(indexId).invalidateIndexedStateForFile(fileId);
          }
        }

        // the file is for sure not a dir and it was previously indexed by at least one index
        if (file.isValid()) {
          if (!isTooLarge(file)) {
            getChangedFilesCollector().scheduleForUpdate(file);
          }
          else getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub((VirtualFileWithId)file));
        }
        else {
          LOG.info("Unexpected state in update:" + file);
        }
      }
    }
    else { // file was removed
      for (ID<?, ?> indexId : nontrivialFileIndexedStates) {
        if (!myRegisteredIndexes.isContentDependentIndex(indexId)) {
          updateSingleIndex(indexId, null, fileId, null);
        }
      }
      if (!fileIndexedStatesToUpdate.isEmpty()) {
        // its data should be (lazily) wiped for every index
        getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub((VirtualFileWithId)file));
      }
      else {
        getChangedFilesCollector().removeScheduledFileFromUpdate(file); // no need to update it anymore
      }
    }
  }

  void scheduleFileForIndexing(int fileId, @NotNull VirtualFile file, boolean contentChange) {
    final List<IndexableFilesFilter> filters = IndexableFilesFilter.EP_NAME.getExtensionList();
    if (!filters.isEmpty() && !ContainerUtil.exists(filters, e -> e.shouldIndex(file))) return;

    // handle 'content-less' indices separately
    boolean fileIsDirectory = file.isDirectory();
    IndexedFileImpl indexedFile = new IndexedFileImpl(file, ProjectUtil.guessProjectForFile(file));

    if (!contentChange) {
      FileContent fileContent = null;

      for (ID<?, ?> indexId : getContentLessIndexes(fileIsDirectory)) {
        if (acceptsInput(indexId, indexedFile)) {
          if (fileContent == null) {
            fileContent = new IndexedFileWrapper(indexedFile);
          }
          updateSingleIndex(indexId, file, fileId, fileContent);
        }
      }
    }
    // For 'normal indices' schedule the file for update and reset stamps for all affected indices (there
    // can be client that used indices between before and after events, in such case indices are up to date due to force update
    // with old content)
    if (!fileIsDirectory) {
      if (!file.isValid() || isTooLarge(file)) {
        // large file might be scheduled for update in before event when its size was not large
        getChangedFilesCollector().removeScheduledFileFromUpdate(file);
      }
      else {
        ourFileToBeIndexed.set(file);
        try {
          FileTypeManagerEx.getInstanceEx().freezeFileTypeTemporarilyIn(file, () -> {
            List<ID<?, ?>> candidates = getAffectedIndexCandidates(indexedFile);

            boolean scheduleForUpdate = false;

            //noinspection ForLoopReplaceableByForEach
            for (int i = 0, size = candidates.size(); i < size; ++i) {
              final ID<?, ?> indexId = candidates.get(i);
              if (needsFileContentLoading(indexId) && acceptsInput(indexId, indexedFile)) {
                getIndex(indexId).invalidateIndexedStateForFile(fileId);
                scheduleForUpdate = true;
              }
            }

            if (scheduleForUpdate) {
              IndexingStamp.flushCache(fileId);
              getChangedFilesCollector().scheduleForUpdate(file);
            }
            else if (file instanceof VirtualFileSystemEntry) {
              ((VirtualFileSystemEntry)file).setFileIndexed(true);
            }
          });
        } finally {
          ourFileToBeIndexed.remove();
        }
      }
    }
    else if (file instanceof VirtualFileSystemEntry) {
      ((VirtualFileSystemEntry)file).setFileIndexed(true);
    }
  }

  @NotNull
  Collection<ID<?, ?>> getContentLessIndexes(boolean isDirectory) {
    return isDirectory ? myRegisteredIndexes.getIndicesForDirectories() : myRegisteredIndexes.getNotRequiringContentIndices();
  }

  @NotNull
  public Collection<ID<?, ?>> getContentDependentIndexes() {
    return myRegisteredIndexes.getRequiringContentIndices();
  }

  void clearUpToDateIndexesForUnsavedOrTransactedDocs() {
    if (!myUpToDateIndicesForUnsavedOrTransactedDocuments.isEmpty()) {
      myUpToDateIndicesForUnsavedOrTransactedDocuments.clear();
    }
  }

  FileIndexingState shouldIndexFile(@NotNull IndexedFile file, @NotNull ID<?, ?> indexId) {
    if (!acceptsInput(indexId, file)) {
      return getIndexingState(file, indexId) == FileIndexingState.NOT_INDEXED
             ? FileIndexingState.UP_TO_DATE
             : FileIndexingState.OUT_DATED;
    }
    return getIndexingState(file, indexId);
  }

  @NotNull
  private FileIndexingState getIndexingState(@NotNull IndexedFile file, @NotNull ID<?, ?> indexId) {
    VirtualFile virtualFile = file.getFile();
    if (isMock(virtualFile)) return FileIndexingState.NOT_INDEXED;
    return getIndex(indexId).getIndexingStateForFile(((NewVirtualFile)virtualFile).getId(), file);
  }

  static boolean isMock(final VirtualFile file) {
    return !(file instanceof NewVirtualFile);
  }

  public boolean isTooLarge(@NotNull VirtualFile file) {
    return isTooLarge(file, null);
  }

  public boolean isTooLarge(@NotNull VirtualFile file,
                            @Nullable("if content size should be retrieved from a file") Long contentSize) {
    return isTooLarge(file, contentSize, myRegisteredIndexes.getNoLimitCheckFileTypes());
  }

  @ApiStatus.Internal
  public static boolean isTooLarge(@NotNull VirtualFile file,
                                   @Nullable("if content size should be retrieved from a file") Long contentSize,
                                   @NotNull Set<FileType> noLimitFileTypes) {
    if (SingleRootFileViewProvider.isTooLargeForIntelligence(file, contentSize)) {
      return !noLimitFileTypes.contains(file.getFileType()) || SingleRootFileViewProvider.isTooLargeForContentLoading(file, contentSize);
    }
    return false;
  }

  public void registerIndexableSet(@NotNull IndexableFileSet set, @NotNull Project project) {
    myIndexableSets.add(set);
    myIndexableSetToProjectMap.put(set, project);
  }

  private boolean acceptsInput(@NotNull ID<?, ?> indexId, @NotNull IndexedFile indexedFile) {
    InputFilter filter = getInputFilter(indexId);
    return acceptsInput(filter, indexedFile);
  }

  public void removeIndexableSet(@NotNull IndexableFileSet set) {
    if (!myIndexableSetToProjectMap.containsKey(set)) return;
    myIndexableSets.remove(set);
    myIndexableSetToProjectMap.remove(set);

    ChangedFilesCollector changedFilesCollector = getChangedFilesCollector();
    for (VirtualFile file : changedFilesCollector.getAllFilesToUpdate()) {
      final int fileId = getFileId(file);
      if (!file.isValid()) {
        removeDataFromIndicesForFile(fileId, file);
        changedFilesCollector.removeFileIdFromFilesScheduledForUpdate(fileId);
      }
      else if (getIndexableSetForFile(file) == null) { // todo remove data from indices for removed
        changedFilesCollector.removeFileIdFromFilesScheduledForUpdate(fileId);
      }
    }

    IndexingStamp.flushCaches();
  }

  @Override
  public VirtualFile findFileById(Project project, int id) {
    return ManagingFS.getInstance().findFileById(id);
  }

  @Nullable
  private static PsiFile findLatestKnownPsiForUncomittedDocument(@NotNull Document doc, @NotNull Project project) {
    return PsiDocumentManager.getInstance(project).getCachedPsiFile(doc);
  }

  @VisibleForTesting
  public static void cleanupProcessedFlag() {
    final VirtualFile[] roots = ManagingFS.getInstance().getRoots();
    for (VirtualFile root : roots) {
      cleanProcessedFlag(root);
    }
  }

  static void cleanProcessedFlag(@NotNull final VirtualFile file) {
    if (!(file instanceof VirtualFileSystemEntry)) return;

    final VirtualFileSystemEntry nvf = (VirtualFileSystemEntry)file;
    nvf.setFileIndexed(false);
    if (file.isDirectory()) {
      for (VirtualFile child : nvf.getCachedChildren()) {
        cleanProcessedFlag(child);
      }
    }
  }

  void setUpFlusher() {
    myFlushingFuture = FlushingDaemon.everyFiveSeconds(new Runnable() {
      private int lastModCount;

      @Override
      public void run() {
        int currentModCount = myLocalModCount.get();
        if (lastModCount == currentModCount) {
          flushAllIndices(lastModCount);
        }
        lastModCount = currentModCount;
      }
    });
  }

  @Override
  public void invalidateCaches() {
    CorruptionMarker.requestInvalidation();
  }

  @Override
  public boolean isFileIndexedInCurrentSession(@NotNull VirtualFile file, @NotNull ID<?, ?> indexId) {
    if (!file.isValid() ||
        !(file instanceof VirtualFileSystemEntry) ||
        !(((VirtualFileSystemEntry)file).isFileIndexed())) return false;

    int fileId = getFileId(file);
    return IndexingStamp.getNontrivialFileIndexedStates(fileId).contains(indexId);
  }

  @Override
  @ApiStatus.Internal
  @NotNull
  public IntPredicate getAccessibleFileIdFilter(@Nullable Project project) {
    boolean dumb = ActionUtil.isDumbMode(project);
    if (!dumb) return f -> true;

    DumbModeAccessType dumbModeAccessType = getCurrentDumbModeAccessType();
    if (dumbModeAccessType == null) {
      //throw new IllegalStateException("index access is not allowed in dumb mode");
      return __ -> true;
    }

    if (dumbModeAccessType == DumbModeAccessType.RAW_INDEX_DATA_ACCEPTABLE) return f -> true;

    assert dumbModeAccessType == DumbModeAccessType.RELIABLE_DATA_ONLY;
    return fileId -> !getChangedFilesCollector().containsFileId(fileId);
  }

  @ApiStatus.Internal
  public void flushIndexes() {
    for (ID<?, ?> id : getRegisteredIndexes().getState().getIndexIDs()) {
      try {
        getIndex(id).flush();
      }
      catch (StorageException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static final boolean DO_TRACE_STUB_INDEX_UPDATE = SystemProperties.getBooleanProperty("idea.trace.stub.index.update", false);

  @ApiStatus.Internal
  static <K, V> int getIndexExtensionVersion(@NotNull FileBasedIndexExtension<K, V> extension) {
    int version = extension.getVersion();

    if (VfsAwareMapReduceIndex.hasSnapshotMapping(extension)) {
      version += SnapshotInputMappings.getVersion();
    }
    return version;
  }

  public static boolean acceptsInput(@NotNull InputFilter filter, @NotNull IndexedFile indexedFile) {
    if (filter instanceof ProjectSpecificInputFilter) {
      if (indexedFile.getProject() == null) {
        Project project = ProjectUtil.guessProjectForFile(indexedFile.getFile());
        ((IndexedFileImpl)indexedFile).setProject(project);
      }
      return ((ProjectSpecificInputFilter)filter).acceptInput(indexedFile);
    }
    return filter.acceptInput(indexedFile.getFile());
  }

  @NotNull
  public static InputFilter composeInputFilter(@NotNull InputFilter filter, @NotNull Predicate<VirtualFile> condition) {
    return filter instanceof ProjectSpecificInputFilter
    ? new ProjectSpecificInputFilter() {
      @Override
      public boolean acceptInput(@NotNull IndexedFile file) {
        return ((ProjectSpecificInputFilter)filter).acceptInput(file) && condition.test(file.getFile());
      }
    }
    : file -> filter.acceptInput(file) && condition.test(file);
  }
}
