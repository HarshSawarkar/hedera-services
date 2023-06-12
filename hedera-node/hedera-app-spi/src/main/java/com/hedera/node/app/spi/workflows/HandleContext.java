/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Represents the context of a single {@code handle()}-call.
 * <p>
 * The information and functionality provided by a {@code HandleContext} is valid only for the duration of the call. It
 * can be grouped into five categories:
 * <ul>
 *     <li>Information about the transaction being handled, such as its consensus time, its body, and its category</li>
 *     <li>Configuration data and objects that depend on the current configuration</li>
 *     <li>Verification data, that has been assembled during pre-handle</li>
 *     <li>State related data and the possibility to rollback changes</li>
 *     <li>Data related to the record stream</li>
 *     <li>Functionality to dispatch preceding and child transactions</li>
 * </ul>
 */
public interface HandleContext {

    /**
     * Category of the current transaction.
     */
    enum TransactionCategory {
        /** The original transaction submitted by a user. */
        USER,

        /** An independent, top-level transaction that is executed before the user transaction. */
        PRECEDING,

        /** A child transaction that is executed as part of a user transaction. */
        CHILD
    }

    /**
     * Returns the current consensus time.
     *
     * @return the current consensus time
     */
    @NonNull
    Instant consensusNow();

    /**
     * Returns the {@link TransactionBody}
     *
     * @return the {@code TransactionBody}
     */
    @NonNull
    TransactionBody body();

    /**
     * Returns the current {@link Configuration} for the node.
     *
     * @return the {@code Configuration}
     */
    @NonNull
    Configuration configuration();

    /**
     * Returns the next entity number, for use by handlers that create entities.
     *
     * <p>If this method is called after a child transaction was dispatched, which is subsequently rolled back,
     * the counter will be rolled back, too. Consequently, the provided number must not be used anymore in this case,
     * because it will be reused.
     *
     * @return the next entity number
     */
    long newEntityNum();

    /**
     * Returns the validator for attributes of entities created or updated by handlers.
     *
     * @return the validator for attributes
     */
    @NonNull
    AttributeValidator attributeValidator();

    /**
     * Returns the validator for expiry metadata (both explicit expiration times and auto-renew configuration) of
     * entities created or updated by handlers.
     *
     * @return the validator for expiry metadata
     */
    @NonNull
    ExpiryValidator expiryValidator();

    /**
     * Gets the {@link SignatureVerification} for the given key. If this key was not provided during pre-handle, then
     * there will be no corresponding {@link SignatureVerification}. If the key was provided during pre-handle, then the
     * corresponding {@link SignatureVerification} will be returned with the result of that verification operation.
     *
     * @param key the key to get the verification for
     * @return the verification for the given key, or {@code null} if no such key was provided during pre-handle
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @Nullable
    SignatureVerification verificationFor(@NonNull Key key);

    /**
     * Gets the {@link SignatureVerification} for the given hollow account.
     *
     * @param evmAlias The evm alias to lookup verification for.
     * @return the verification for the given hollow account.
     */
    @Nullable
    SignatureVerification verificationFor(@NonNull final Bytes evmAlias);

    /**
     * Get a readable store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <T> Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <T> T readableStore(@NonNull Class<T> storeInterface);

    /**
     * Return a writable store given the store's interface. This gives write access to the store.
     *
     * <p>This method is limited to stores that are part of the transaction's service.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <T> Interface class for a Store
     * @return An implementation of the provided store interface
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <T> T writableStore(@NonNull Class<T> storeInterface);

    /**
     * Returns a record builder for the given record builder subtype.
     *
     * @param recordBuilderClass the record type
     * @param <T> the record type
     * @return a builder for the given record type
     * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    @NonNull
    <T> T recordBuilder(@NonNull Class<T> recordBuilderClass);

    /**
     * Dispatches an independent (top-level) transaction, that precedes the current transaction.
     *
     * <p>A top-level transaction is independent of any other transaction. If it is successful, the state changes are
     * automatically committed. If it fails, any eventual state changes are automatically rolled back.
     *
     * <p>This method can only be called my a {@link TransactionCategory#USER}-transaction and only as long as no state
     * changes have been introduced by the user transaction (either by storing state or by calling a child
     * transaction).
     *
     * @param txBody the {@link TransactionBody} of the transaction to dispatch
     * @param recordBuilderClass the record builder class of the transaction
     * @return the record builder of the transaction
     * @throws NullPointerException if {@code txBody} is {@code null}
     * @throws IllegalArgumentException if the transaction is not a {@link TransactionCategory#USER}-transaction or if
     * the record builder type is unknown to the app
     * @throws IllegalStateException if the current transaction has already introduced state changes
     */
    @NonNull
    <T> T dispatchPrecedingTransaction(@NonNull TransactionBody txBody, @NonNull Class<T> recordBuilderClass);

    /**
     * Dispatches a child transaction.
     *
     * <p>A child transaction depends on the current transaction. That means if the current transaction fails,
     * a child transaction is automatically rolled back. The state changes introduced by a child transaction are
     * automatically committed together with the parent transaction.
     *
     * <p>A child transaction will run with the current state. It will see all state changes introduced by the current
     * transaction or preceding child transactions. If successful, a new entry will be added to the
     * {@link SavepointStack}. This enables the current transaction to commit or roll back the state changes. Please be
     * aware that any state changes introduced by storing data in one of the stores after calling a child transaction
     * will also be rolled back if the child transaction is rolled back.
     *
     * <p>A {@link TransactionCategory#PRECEDING}-transaction must not dispatch a child transaction.
     *
     * @param txBody the {@link TransactionBody} of the child transaction to dispatch
     * @param recordBuilderClass the record builder class of the child transaction
     * @return the record builder of the child transaction
     * @throws NullPointerException if {@code txBody} is {@code null}
     * @throws IllegalArgumentException if the current transaction is a
     * {@link TransactionCategory#PRECEDING}-transaction or if the record builder type is unknown to the app
     */
    @NonNull
    <T> T dispatchChildTransaction(@NonNull TransactionBody txBody, @NonNull Class<T> recordBuilderClass);

    /**
     * Dispatches a removable child transaction.
     *
     * <p>A removable child transaction depends on the current transaction. It behaves in almost all aspects like a
     * regular child transaction (see {@link #dispatchChildTransaction(TransactionBody, Class)}. But unlike regular
     * child transactions, the records of removable child transactions are removed and not reverted.
     *
     * @param txBody the {@link TransactionBody} of the child transaction to dispatch
     * @param recordBuilderClass the record builder class of the child transaction
     * @return the record builder of the child transaction
     * @throws NullPointerException if {@code txBody} is {@code null}
     * @throws IllegalArgumentException if the current transaction is a
     * {@link TransactionCategory#PRECEDING}-transaction or if the record builder type is unknown to the app
     */
    @NonNull
    <T> T dispatchRemovableChildTransaction(@NonNull TransactionBody txBody, @NonNull Class<T> recordBuilderClass);

    /**
     * Adds a child record builder to the list of record builders. If the current {@link HandleContext} (or any parent
     * context) is rolled back, all child record builders will be reverted.
     *
     * @param recordBuilderClass the record type
     * @return the new child record builder
     * @param <T> the record type
     * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    @NonNull
    <T> T addChildRecordBuilder(@NonNull Class<T> recordBuilderClass);

    /**
     * Adds a removable child record builder to the list of record builders. Unlike a regular child record builder,
     * a removable child record builder is removed, if the current {@link HandleContext} (or any parent context) is
     * rolled back.
     *
     * @param recordBuilderClass the record type
     * @return the new child record builder
     * @param <T> the record type
     * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
     * @throws IllegalArgumentException if the record builder type is unknown to the app
     */
    @NonNull
    <T> T addRemovableChildRecordBuilder(@NonNull Class<T> recordBuilderClass);

    /**
     * Returns the current {@link SavepointStack}.
     *
     * @return the current {@code TransactionStack}
     */
    @NonNull
    SavepointStack savepointStack();

    /**
     * A stack of savepoints.
     *
     * <p>A new savepoint can be created manually. In addition, a new entry is added to the savepoint stack every time
     * a child transaction is dispatched and executed successfully. The transaction stack allows to rollback an
     * arbitrary number of transactions. Please be aware that rolling back a child transaction will also rollbacks all
     * state changes that were introduced afterward.
     */
    interface SavepointStack {
        /**
         * Create a savepoint manually.
         * <p>
         * This method will add a new entry to the savepoint stack. A subsequent rollback will roll back all state
         * changes that were introduced after the savepoint was added.
         */
        void createSavepoint();

        /**
         * Rolls back the changes up until the last savepoint.
         *
         * @throws IllegalStateException if the savepoint stack is empty
         */
        default void rollback() {
            rollback(1);
        }

        /**
         * Rolls back the last {@code count} savepoints.
         *
         * @param count the number of savepoints to roll back
         * @throws IllegalArgumentException if {@code count} is less than {@code 1}
         * @throws IllegalStateException if the transaction stack contains fewer elements than {@code count}
         */
        void rollback(int count);

        /**
         * Returns the depth of the savepoint stack.
         *
         * @return the depth of the savepoint stack
         */
        int depth();
    }
}