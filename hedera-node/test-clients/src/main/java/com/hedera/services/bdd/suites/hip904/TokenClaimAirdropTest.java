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

package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.leaky.LeakyContractTestsSuite.RECEIVER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_PENDING_AIRDROP_ID_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@HapiTestLifecycle
@DisplayName("Claim token airdrop")
public class TokenClaimAirdropTest extends TokenAirdropBase {
    private static final String OWNER_2 = "owner2";
    private static final String FUNGIBLE_TOKEN_1 = "fungibleToken1";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String FUNGIBLE_TOKEN_3 = "fungibleToken3";
    private static final String FUNGIBLE_TOKEN_4 = "fungibleToken4";
    private static final String FUNGIBLE_TOKEN_5 = "fungibleToken5";
    private static final String FUNGIBLE_TOKEN_6 = "fungibleToken6";
    private static final String FUNGIBLE_TOKEN_7 = "fungibleToken7";
    private static final String FUNGIBLE_TOKEN_8 = "fungibleToken8";
    private static final String FUNGIBLE_TOKEN_9 = "fungibleToken9";
    private static final String FUNGIBLE_TOKEN_10 = "fungibleToken10";
    private static final String FUNGIBLE_TOKEN_11 = "fungibleToken11";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String NFT_SUPPLY_KEY = "supplyKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "entities.unlimitedAutoAssociationsEnabled", "false",
                "tokens.airdrops.enabled", "false",
                "tokens.airdrops.claim.enabled", "false"));
        // create some entities with disabled airdrops
        lifecycle.doAdhoc(setUpEntitiesPreHIP904());
        // enable airdrops
        lifecycle.doAdhoc(
                overriding("entities.unlimitedAutoAssociationsEnabled", "true"),
                overriding("tokens.airdrops.enabled", "true"),
                overriding("tokens.airdrops.claim.enabled", "true"));
    }

    @HapiTest
    @DisplayName("Claim token airdrop after dissociation")
    final Stream<DynamicTest> claimTokenAirdropAfterDissociation() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),

                // do token association and dissociation
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                tokenDissociate(RECEIVER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),

                // do pending airdrop
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(OWNER),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingNFTAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(RECEIVER)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))
                                .tokenTransfers(includingNonfungibleMovement(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert balances
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                getAccountBalance(RECEIVER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountInfo(RECEIVER).hasToken(relationshipWith(NON_FUNGIBLE_TOKEN))));
    }

    @HapiTest
    final Stream<DynamicTest> claimFungibleTokenAirdrop() {
        return defaultHapiSpec("should transfer fungible tokens")
                .given(flattened(
                        setUpTokensAndAllReceivers(), cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS)))
                .when(
                        // do pending airdrop
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                                .payingWith(OWNER),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                                .payingWith(OWNER),

                        // do claim
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                        pendingNFTAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                                .payingWith(RECEIVER)
                                .via("claimTxn"))
                .then( // assert txn record
                        getTxnRecord("claimTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(
                                                moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))
                                        .tokenTransfers(includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 1)
                                                .between(OWNER, RECEIVER)))),
                        validateChargedUsd("claimTxn", 0.001, 1),
                        // assert balance fungible tokens
                        getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                        // assert balances NFT
                        getAccountBalance(RECEIVER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                        // assert token associations
                        getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                        getAccountInfo(RECEIVER).hasToken(relationshipWith(NON_FUNGIBLE_TOKEN)));
    }

    @HapiTest
    @DisplayName("single token claim success that receiver paying for it")
    final Stream<DynamicTest> singleTokenClaimSuccessThatReceiverPayingForIt() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN_1, 999),
                getAccountInfo(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasToken(relationshipWith(FUNGIBLE_TOKEN_1)));
    }

    @HapiTest
    @DisplayName("single nft claim success that receiver paying for it")
    final Stream<DynamicTest> singleNFTTransfer() {
        return hapiTest(
                cryptoCreate(OWNER_2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed(NFT_SUPPLY_KEY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER_2)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(NFT_SUPPLY_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER_2, RECEIVER))
                        .payingWith(OWNER_2),
                tokenClaimAirdrop(pendingNFTAirdrop(OWNER_2, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(RECEIVER));
    }

    @HapiTest
    @DisplayName("not enough Hbar to claim and than enough")
    final Stream<DynamicTest> notEnoughHbarToCalimAndThanEnough() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(0L).maxAutomaticTokenAssociations(0),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(BOB).balance(ONE_MILLION_HBARS),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(OWNER, ALICE)).payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, ALICE, FUNGIBLE_TOKEN_1))
                        .payingWith(ALICE)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                cryptoTransfer(tinyBarsFromTo(BOB, ALICE, ONE_HBAR)),
                tokenClaimAirdrop(pendingAirdrop(OWNER, ALICE, FUNGIBLE_TOKEN_1))
                        .payingWith(ALICE));
    }

    @HapiTest
    @DisplayName("token transfer fails but claim will pass")
    final Stream<DynamicTest> tokenTransferFailsButClaimWillPass() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                cryptoTransfer(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("change a treasury owner")
    final Stream<DynamicTest> tokenTChangeTreasuryOwner() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAssociate(CAROL, FUNGIBLE_TOKEN_1),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenUpdate(FUNGIBLE_TOKEN_1).treasury(CAROL).payingWith(ALICE),
                cryptoTransfer(moving(20, FUNGIBLE_TOKEN_1).between(CAROL, ALICE)),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("token association after the token airdrop")
    final Stream<DynamicTest> tokenAssociationAfterTokenAirdrop() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAssociate(BOB, FUNGIBLE_TOKEN_1),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("multiple token airdrops one NFT and Than another NFT and claims them separately")
    final Stream<DynamicTest> twoAirdropsNFTSandTwoClaims() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed(NFT_SUPPLY_KEY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .treasury(ALICE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(NFT_SUPPLY_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenClaimAirdrop(pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(BOB),
                tokenClaimAirdrop(pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 2))
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2));
    }

    @HapiTest
    @DisplayName("Claim token airdrop - 2nd account pays")
    final Stream<DynamicTest> claimTokenAirdropOtherAccountPays() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(OWNER_2).balance(ONE_HUNDRED_HBARS),

                // do pending airdrop
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)).payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                        .signedBy(OWNER_2, RECEIVER)
                        .payingWith(OWNER_2)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
    }

    @HapiTest
    @DisplayName("Claim token airdrop - sender account pays")
    final Stream<DynamicTest> claimTokenAirdropSenderAccountPays() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(0L),

                // do pending airdrop
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)).payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                        .signedBy(OWNER, RECEIVER)
                        .payingWith(OWNER)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
    }

    @HapiTest
    @DisplayName("multiple FT airdrops to same receiver")
    final Stream<DynamicTest> multipleFtAirdropsSameReceiver() {
        final String BOB = "BOB";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, BOB, FUNGIBLE_TOKEN))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN, 30)));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop same token different receivers")
    final Stream<DynamicTest> multiplePendingInOneAirdropDifferentReceivers() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, CAROL))
                        .payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_1))
                        .signedBy(BOB, CAROL)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(CAROL).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop different token same receivers with max auto association")
    final Stream<DynamicTest> multiplePendingInOneAirdropSameReceiverDifferentTokensWithMaxAutoAssociation() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_2).between(ALICE, BOB)).payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 1));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop different token same receivers with specific association")
    final Stream<DynamicTest> multiplePendingInOneAirdropSameReceiverDifferentTokensWithSpecificAssociation() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 1000L),
                tokenAssociate(BOB, FUNGIBLE_TOKEN_1).payingWith(ALICE),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_2).between(ALICE, BOB))
                        .payingWith(ALICE),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 0),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 1));
    }

    @HapiTest
    @DisplayName("token claim with no pending airdrop should fail")
    final Stream<DynamicTest> tokenClaimWithNoPendingAirdrop() {
        return hapiTest(tokenClaimAirdrop().hasPrecheck(EMPTY_PENDING_AIRDROP_ID_LIST));
    }

    @HapiTest
    @DisplayName("token claim with duplicate entires should fail")
    final Stream<DynamicTest> duplicateClaimAirdrop() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED));
    }

    @HapiTest
    @DisplayName("token claim with more than ten pending airdrops should fail")
    final Stream<DynamicTest> tokenClaimWithMoreThanTenPendingAirdropsShouldFail() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_2, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_3, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_4, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_5, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_6, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_7, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_8, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_9, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_10, OWNER, 1000L),
                createFT(FUNGIBLE_TOKEN_11, OWNER, 1000L),
                airdropFT(FUNGIBLE_TOKEN_1, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_2, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_3, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_4, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_5, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_6, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_7, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_8, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_9, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_10, OWNER, RECEIVER, 20),
                airdropFT(FUNGIBLE_TOKEN_11, OWNER, RECEIVER, 20),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_8),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_9),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_10),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_11))
                        .payingWith(RECEIVER)
                        .via("claimTxn")
                        .hasKnownStatus(PENDING_AIRDROP_ID_LIST_TOO_LONG));
    }

    @HapiTest
    @DisplayName("Claim frozen token airdrop")
    final Stream<DynamicTest> claimFrozenToken() {
        final var tokenFreezeKey = "freezeKey";
        return defaultHapiSpec("should fail - ACCOUNT_FROZEN_FOR_TOKEN")
                .given(flattened(
                        setUpTokensAndAllReceivers(),
                        newKeyNamed(tokenFreezeKey),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .treasury(OWNER)
                                .freezeKey(tokenFreezeKey)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)))
                .when(
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                                .payingWith(OWNER),
                        tokenFreeze(FUNGIBLE_TOKEN, OWNER))
                .then(
                        getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                                .payingWith(RECEIVER)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 0));
    }

    @HapiTest
    @DisplayName("hollow account with 0 free maxAutoAssociations")
    final Stream<DynamicTest> airdropToAliasWithNoFreeSlots() {
        final var validAlias = "validAlias";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, validAlias))
                        .payingWith(OWNER),
                // check if account is hollow (has empty key)
                getAliasedAccountInfo(validAlias)
                        .has(accountWith().hasEmptyKey().noAlias()),

                // claim and check if account is finalized
                tokenClaimAirdrop(pendingAirdrop(OWNER, validAlias, FUNGIBLE_TOKEN))
                        .payingWith(validAlias)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(validAlias))
                        .via("claimTxn"),
                validateChargedUsd("claimTxn", 0.001, 1),

                // check if account was finalized (has the correct key)
                getAliasedAccountInfo(validAlias)
                        .has(accountWith().key(validAlias).noAlias())));
    }

    @HapiTest
    @DisplayName("given two same claims, second should fail")
    final Stream<DynamicTest> twoSameClaims() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn1")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn2")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                validateChargedUsd("claimTxn1", 0.001, 1),
                validateChargedUsd("claimTxn2", 0.001, 1)));
    }

    @HapiTest
    @DisplayName("missing pending airdrop, claim should fail")
    final Stream<DynamicTest> missingPendingClaim() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("signed by account not referenced as receiver_id in each pending airdrops")
    final Stream<DynamicTest> signedByAccountNotReferencedAsReceiverId() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                // Payer is not receiver
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Missing signature from second receiver
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn1")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Succeeds with all required signatures
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .signedBy(
                                DEFAULT_PAYER,
                                RECEIVER_WITH_0_AUTO_ASSOCIATIONS,
                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                validateChargedUsd("claimTxn", 0.001, 1)));
    }

    @HapiTest
    @DisplayName("spender has insufficient balance")
    final Stream<DynamicTest> spenderHasInsufficientBalance() {
        var spender = "spenderWithInsufficientBalance";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(spender).balance(ONE_HBAR).maxAutomaticTokenAssociations(-1),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, spender)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(spender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(spender),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(spender, OWNER)),
                tokenClaimAirdrop(pendingAirdrop(spender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    @DisplayName("duplicate entries of Non Fungible Tokens should fail")
    final Stream<DynamicTest> duplicatedNFTFail() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED)));
    }

    @HapiTest
    @DisplayName("containing up to 10 tokens and some duplicate entries should fail")
    final Stream<DynamicTest> contain10TokensDulplicateFail() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1001L),
                createFT(FUNGIBLE_TOKEN_2, OWNER, 1002L),
                createFT(FUNGIBLE_TOKEN_3, OWNER, 1003L),
                createFT(FUNGIBLE_TOKEN_4, OWNER, 1004L),
                createFT(FUNGIBLE_TOKEN_5, OWNER, 1005L),
                createFT(FUNGIBLE_TOKEN_6, OWNER, 1006L),
                createFT(FUNGIBLE_TOKEN_7, OWNER, 1007L),
                createFT(FUNGIBLE_TOKEN_8, OWNER, 1008L),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_8))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED)));
    }

    @HapiTest
    @DisplayName(
            "not signed by the account referenced by a receiver_id for each entry in the pending airdrops list should fail")
    final Stream<DynamicTest> notSignedByReceiverFail() {
        var RECEIVER2 = "RECEIVER2";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER2, NON_FUNGIBLE_TOKEN))
                        .signedBy(RECEIVER)
                        .hasPrecheck(INVALID_SIGNATURE)));
    }

    @LeakyHapiTest(overrides = {"tokens.airdrops.claim.enabled"})
    @DisplayName("sign a tokenClaimAirdrop transaction when the feature flag is disabled")
    final Stream<DynamicTest> tokenClaimAirdropDisabledFail() {
        return hapiTest(
                overriding("tokens.airdrops.claim.enabled", "false"),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    private HapiTokenCreate createFT(String tokenName, String treasury, long amount) {
        return tokenCreate(tokenName)
                .treasury(treasury)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(amount);
    }

    private HapiTokenAirdrop airdropFT(String tokenName, String sender, String receiver, int amountToMove) {
        return tokenAirdrop(moving(amountToMove, tokenName).between(sender, receiver))
                .payingWith(sender);
    }
}
