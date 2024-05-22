/*
 * Copyright (C) 2018-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.snapshot;

import static com.swirlds.common.io.utility.FileUtils.deleteDirectoryAndLog;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STATE_TO_DISK;
import static com.swirlds.platform.state.snapshot.StateToDiskReason.UNKNOWN;

import com.swirlds.base.time.Time;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.utility.Threshold;
import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.payload.InsufficientSignaturesPayload;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.events.EventConstants;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible for managing the state writing pipeline.
 */
public class DefaultStateSnapshotManager implements StateSnapshotManager {

    private static final Logger logger = LogManager.getLogger(DefaultStateSnapshotManager.class);

    /**
     * The ID of this node.
     */
    private final NodeId selfId;

    /**
     * The name of the application that is currently running.
     */
    private final String mainClassName;

    /**
     * The swirld name.
     */
    private final String swirldName;

    /**
     * Metrics provider
     */
    private final StateSnapshotManagerMetrics metrics;

    /**
     * the configuration
     */
    private final Configuration configuration;

    /**
     * the platform context
     */
    private final PlatformContext platformContext;

    /**
     * Provides system time
     */
    private final Time time;

    /**
     * Used to determine the path of a signed state
     */
    private final SignedStateFilePath signedStateFilePath;

    /**
     * Creates a new instance.
     *
     * @param platformContext       the platform context
     * @param mainClassName the main class name of this node
     * @param selfId        the ID of this node
     * @param swirldName    the name of the swirld
     */
    public DefaultStateSnapshotManager(
            @NonNull final PlatformContext platformContext,
            @NonNull final String mainClassName,
            @NonNull final NodeId selfId,
            @NonNull final String swirldName) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.time = platformContext.getTime();
        this.selfId = Objects.requireNonNull(selfId);
        this.mainClassName = Objects.requireNonNull(mainClassName);
        this.swirldName = Objects.requireNonNull(swirldName);
        configuration = platformContext.getConfiguration();
        signedStateFilePath = new SignedStateFilePath(configuration.getConfigData(StateCommonConfig.class));
        metrics = new StateSnapshotManagerMetrics(platformContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public StateSavingResult saveStateTask(@NonNull final ReservedSignedState reservedSignedState) {
        final long start = time.nanoTime();
        final StateSavingResult stateSavingResult;

        // the state is reserved before it is handed to this method, and it is released when we are done
        try (reservedSignedState) {
            final SignedState signedState = reservedSignedState.get();
            if (signedState.hasStateBeenSavedToDisk()) {
                logger.info(
                        EXCEPTION.getMarker(),
                        "Not saving signed state for round {} to disk because it has already been saved.",
                        signedState.getRound());
                return null;
            }
            checkSignatures(signedState);
            final boolean success = saveStateTask(signedState, getSignedStateDir(signedState.getRound()));
            if (!success) {
                return null;
            }
            signedState.stateSavedToDisk();
            final long minGen = deleteOldStates();
            stateSavingResult = new StateSavingResult(
                    signedState.getRound(), signedState.isFreezeState(), signedState.getConsensusTimestamp(), minGen);
        }
        metrics.getStateToDiskTimeMetric().update(TimeUnit.NANOSECONDS.toMillis(time.nanoTime() - start));
        metrics.getWriteStateToDiskTimeMetric().update(TimeUnit.NANOSECONDS.toMillis(time.nanoTime() - start));

        return stateSavingResult;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dumpStateTask(@NonNull final StateDumpRequest request) {
        // the state is reserved before it is handed to this method, and it is released when we are done
        try (final ReservedSignedState reservedSignedState = request.reservedSignedState()) {
            final SignedState signedState = reservedSignedState.get();
            // states requested to be written out-of-band are always written to disk
            saveStateTask(
                    reservedSignedState.get(),
                    signedStateFilePath
                            .getSignedStatesBaseDirectory()
                            .resolve(getReason(signedState).getDescription())
                            .resolve(String.format("node%d_round%d", selfId.id(), signedState.getRound())));
        }
        request.finishedCallback().run();
    }

    @NonNull
    private static StateToDiskReason getReason(@NonNull final SignedState state) {
        return Optional.ofNullable(state.getStateToDiskReason()).orElse(UNKNOWN);
    }

    private boolean saveStateTask(@NonNull final SignedState state, @NonNull final Path directory) {
        try {
            SignedStateFileWriter.writeSignedStateToDisk(platformContext, selfId, directory, state, getReason(state));
            return true;
        } catch (final Throwable e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Unable to write signed state to disk for round {} to {}.",
                    state.getRound(),
                    directory,
                    e);
            return false;
        }
    }

    /**
     * Checks if the state has enough signatures to be written to disk. If it does not, it logs an error and increments
     * the appropriate metric.
     *
     * @param reservedState the state being written to disk
     */
    private void checkSignatures(@NonNull final SignedState reservedState) {
        // this is debug information for ticket #11422
        final long signingWeight1 = reservedState.getSigningWeight();
        final long totalWeight1 = reservedState.getAddressBook().getTotalWeight();
        if (reservedState.isComplete()) {
            // state is complete, nothing to do
            return;
        }
        metrics.getTotalUnsignedDiskStatesMetric().increment();

        final long signingWeight2 = reservedState.getSigningWeight();
        final long totalWeight2 = reservedState.getAddressBook().getTotalWeight();

        // don't log an error if this is a freeze state. they are expected to lack signatures
        if (reservedState.isFreezeState()) {
            logger.info(
                    STATE_TO_DISK.getMarker(),
                    """
                            Freeze state written to disk for round {} was not fully signed. This is expected.
                            Collected signatures representing {}/{} ({}%) weight.
                            """,
                    reservedState.getRound(),
                    reservedState.getSigningWeight(),
                    reservedState.getAddressBook().getTotalWeight(),
                    reservedState.getSigningWeight()
                            / reservedState.getAddressBook().getTotalWeight()
                            * 100.0);
        } else {
            logger.error(
                    EXCEPTION.getMarker(),
                    new InsufficientSignaturesPayload(
                            ("""
                                    State written to disk for round %d did not have enough signatures.
                                    This log adds debug information for #11422.
                                    Pre-check weight: %d/%d (%f%%)  Post-check weight: %d/%d (%f%%)
                                    Pre-check threshold: %s   Post-check threshold: %s"""
                                    .formatted(
                                            reservedState.getRound(),
                                            signingWeight1,
                                            totalWeight1,
                                            signingWeight1 / totalWeight1 * 100.0,
                                            signingWeight2,
                                            totalWeight2,
                                            signingWeight2 / totalWeight2 * 100.0,
                                            Threshold.SUPER_MAJORITY.isSatisfiedBy(signingWeight1, totalWeight1),
                                            Threshold.SUPER_MAJORITY.isSatisfiedBy(signingWeight2, totalWeight2)))));
        }
    }

    /**
     * Get the directory for a particular signed state. This directory might not exist
     *
     * @param round the round number for the signed state
     * @return the File that represents the directory of the signed state for the particular round
     */
    @NonNull
    private Path getSignedStateDir(final long round) {
        return signedStateFilePath.getSignedStateDirectory(mainClassName, selfId, swirldName, round);
    }

    /**
     * Purge old states on the disk.
     *
     * @return the minimum generation non-ancient of the oldest state that was not deleted
     */
    private long deleteOldStates() {
        final List<SavedStateInfo> savedStates =
                signedStateFilePath.getSavedStateFiles(mainClassName, selfId, swirldName);

        // States are returned newest to oldest. So delete from the end of the list to delete the oldest states.
        int index = savedStates.size() - 1;
        final StateConfig stateConfig = configuration.getConfigData(StateConfig.class);
        for (; index >= stateConfig.signedStateDisk(); index--) {

            final SavedStateInfo savedStateInfo = savedStates.get(index);
            try {
                deleteDirectoryAndLog(savedStateInfo.getDirectory());
            } catch (final IOException e) {
                // Intentionally ignored, deleteDirectoryAndLog will log any exceptions that happen
            }
        }

        if (index < 0) {
            return EventConstants.GENERATION_UNDEFINED;
        }
        final SavedStateMetadata oldestStateMetadata = savedStates.get(index).metadata();
        return oldestStateMetadata.minimumGenerationNonAncient();
    }
}