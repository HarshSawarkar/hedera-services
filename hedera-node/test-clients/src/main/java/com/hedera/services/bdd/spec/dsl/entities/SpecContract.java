/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.spec.dsl.entities;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.PBJ_IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.PROTO_IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.atMostOnce;
import static com.hedera.services.bdd.spec.dsl.utils.DslUtils.withSubstitutedTypes;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createLargeFile;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.getInitcodeOf;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.services.bdd.SpecOperation;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.EvmAddressableEntity;
import com.hedera.services.bdd.spec.dsl.SpecEntity;
import com.hedera.services.bdd.spec.dsl.operations.queries.GetContractInfoOperation;
import com.hedera.services.bdd.spec.dsl.operations.queries.StaticCallContractOperation;
import com.hedera.services.bdd.spec.dsl.operations.transactions.CallContractOperation;
import com.hedera.services.bdd.spec.dsl.utils.KeyMetadata;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.utilops.grouping.InBlockingOrder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a Hedera account that may exist on one or more target networks and be
 * registered with more than one {@link HapiSpec} if desired.
 */
public class SpecContract extends AbstractSpecEntity<SpecOperation, Account>
        implements SpecEntity, EvmAddressableEntity {
    private static final int MAX_INLINE_INITCODE_SIZE = 4096;

    private final long creationGas;
    private final String contractName;
    private final Account.Builder builder = Account.newBuilder();
    /**
     * The constructor arguments for the contract's creation call; if the arguments are
     * not constant values, must be set imperatively within the HapiTest context instead
     * of via @ContractSpec annotation attribute.
     */
    private Object[] constructorArgs = new Object[0];

    public SpecContract(@NonNull final String name, @NonNull final String contractName, final long creationGas) {
        super(name);
        this.creationGas = creationGas;
        this.contractName = requireNonNull(contractName);
    }

    /**
     * Returns a builder for the model account to be created, or throws if the entity is locked.
     *
     * @return the builder
     */
    public Account.Builder builder() {
        throwIfLocked();
        return builder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address addressOn(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        final var networkContract = contractOrThrow(network);
        return headlongAddressOf(networkContract);
    }

    /**
     * Returns an operation that retrieves the contract information.
     *
     * @return the operation
     */
    public GetContractInfoOperation getInfo() {
        return new GetContractInfoOperation(this);
    }

    /**
     * Returns an operation that calls a function on the contract.
     *
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public CallContractOperation call(@NonNull final String function, @NonNull final Object... parameters) {
        return new CallContractOperation(this, function, parameters);
    }

    /**
     * Returns an operation that static calls a function on the contract.
     *
     * @param function the function name
     * @param parameters the function parameters
     * @return the operation
     */
    public StaticCallContractOperation staticCall(@NonNull final String function, @NonNull final Object... parameters) {
        return new StaticCallContractOperation(this, function, parameters);
    }

    /**
     * Sets the constructor arguments for the contract's creation call.
     *
     * @param args the arguments
     */
    public void setConstructorArgs(@NonNull final Object... args) {
        this.constructorArgs = args;
    }

    /**
     * Gets the contract model for the given network, or throws if it doesn't exist.
     *
     * @param network the network
     * @return the contract model
     */
    public Account contractOrThrow(@NonNull final HederaNetwork network) {
        return modelOrThrow(network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Creation<SpecOperation, Account> newCreation(@NonNull final HapiSpec spec) {
        final var model = builder.build();
        final var initcode = getInitcodeOf(contractName);
        final SpecOperation op;
        constructorArgs = withSubstitutedTypes(spec.targetNetworkOrThrow(), constructorArgs);
        if (initcode.size() < MAX_INLINE_INITCODE_SIZE) {
            op = contractCreate(name, constructorArgs).inlineInitCode(initcode);
        } else {
            op = blockingOrder(
                    createLargeFile(GENESIS, contractName, initcode),
                    contractCreate(name, constructorArgs).gas(creationGas).bytecode(contractName));
        }
        return new Creation<>(op, model);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Result<Account> resultForSuccessful(
            @NonNull Creation<SpecOperation, Account> creation, @NonNull HapiSpec spec) {
        final HapiContractCreate contractCreate;
        if (creation.op() instanceof HapiContractCreate inlineCreate) {
            contractCreate = inlineCreate;
        } else {
            contractCreate = (HapiContractCreate) ((InBlockingOrder) creation.op()).last();
        }

        final var newContractNum = contractCreate.numOfCreatedContractOrThrow();
        final var maybeKeyMetadata = contractCreate.getAdminKey().map(key -> KeyMetadata.from(key, spec));
        return new Result<>(
                creation.model()
                        .copyBuilder()
                        .smartContract(true)
                        .accountId(AccountID.newBuilder()
                                .accountNum(newContractNum)
                                .build())
                        .key(maybeKeyMetadata.map(KeyMetadata::pbjKey).orElse(PBJ_IMMUTABILITY_SENTINEL_KEY))
                        .build(),
                atMostOnce(siblingSpec -> {
                    maybeKeyMetadata.ifPresentOrElse(
                            keyMetadata -> keyMetadata.registerAs(name, siblingSpec),
                            () -> siblingSpec.registry().saveKey(name, PROTO_IMMUTABILITY_SENTINEL_KEY));
                    siblingSpec
                            .registry()
                            .saveAccountId(
                                    name,
                                    com.hederahashgraph.api.proto.java.AccountID.newBuilder()
                                            .setAccountNum(newContractNum)
                                            .build());
                    siblingSpec
                            .registry()
                            .saveContractId(
                                    name,
                                    com.hederahashgraph.api.proto.java.ContractID.newBuilder()
                                            .setContractNum(newContractNum)
                                            .build());
                    siblingSpec.registry().saveContractInfo(name, contractCreate.infoOfCreatedContractOrThrow());
                }));
    }
}