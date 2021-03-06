/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.governance.blindvote;

import bisq.core.app.BisqEnvironment;
import bisq.core.btc.exceptions.TransactionVerificationException;
import bisq.core.btc.exceptions.WalletException;
import bisq.core.btc.wallet.BsqWalletService;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.btc.wallet.TxBroadcastException;
import bisq.core.btc.wallet.TxBroadcastTimeoutException;
import bisq.core.btc.wallet.TxBroadcaster;
import bisq.core.btc.wallet.TxMalleabilityException;
import bisq.core.btc.wallet.WalletsManager;
import bisq.core.dao.DaoSetupService;
import bisq.core.dao.governance.ballot.BallotList;
import bisq.core.dao.governance.ballot.BallotListService;
import bisq.core.dao.governance.blindvote.storage.BlindVotePayload;
import bisq.core.dao.governance.merit.Merit;
import bisq.core.dao.governance.merit.MeritList;
import bisq.core.dao.governance.myvote.MyVoteListService;
import bisq.core.dao.governance.proposal.MyProposalListService;
import bisq.core.dao.governance.proposal.Proposal;
import bisq.core.dao.governance.proposal.compensation.CompensationProposal;
import bisq.core.dao.governance.voteresult.VoteResultConsensus;
import bisq.core.dao.state.BsqStateListener;
import bisq.core.dao.state.BsqStateService;
import bisq.core.dao.state.blockchain.Block;
import bisq.core.dao.state.period.DaoPhase;
import bisq.core.dao.state.period.PeriodService;

import bisq.network.p2p.P2PService;

import bisq.common.UserThread;
import bisq.common.app.DevEnv;
import bisq.common.crypto.CryptoException;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ExceptionHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.proto.persistable.PersistedDataHost;
import bisq.common.storage.Storage;
import bisq.common.util.Tuple2;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.DeterministicKey;

import javax.inject.Inject;

import javafx.beans.value.ChangeListener;

import javax.crypto.SecretKey;

import java.io.IOException;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * Publishes blind vote tx and blind vote payload to p2p network.
 * Maintains myBlindVoteList for own blind votes. Triggers republishing of my blind votes at startup during blind
 * vote phase of current cycle.
 * Publishes a BlindVote and the blind vote transaction.
 */
@Slf4j
public class MyBlindVoteListService implements PersistedDataHost, BsqStateListener, DaoSetupService {
    private final P2PService p2PService;
    private final BsqStateService bsqStateService;
    private final PeriodService periodService;
    private final WalletsManager walletsManager;
    private final Storage<MyBlindVoteList> storage;
    private final BsqWalletService bsqWalletService;
    private final BtcWalletService btcWalletService;
    private final BallotListService ballotListService;
    private final MyVoteListService myVoteListService;
    private final MyProposalListService myProposalListService;
    private final ChangeListener<Number> numConnectedPeersListener;
    @Getter
    private final MyBlindVoteList myBlindVoteList = new MyBlindVoteList();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MyBlindVoteListService(P2PService p2PService,
                                  BsqStateService bsqStateService,
                                  PeriodService periodService,
                                  WalletsManager walletsManager,
                                  Storage<MyBlindVoteList> storage,
                                  BsqWalletService bsqWalletService,
                                  BtcWalletService btcWalletService,
                                  BallotListService ballotListService,
                                  MyVoteListService myVoteListService,
                                  MyProposalListService myProposalListService) {
        this.p2PService = p2PService;
        this.bsqStateService = bsqStateService;
        this.periodService = periodService;
        this.walletsManager = walletsManager;
        this.storage = storage;
        this.bsqWalletService = bsqWalletService;
        this.btcWalletService = btcWalletService;
        this.ballotListService = ballotListService;
        this.myVoteListService = myVoteListService;
        this.myProposalListService = myProposalListService;

        numConnectedPeersListener = (observable, oldValue, newValue) -> rePublishOnceWellConnected();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoSetupService
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addListeners() {
        bsqStateService.addBsqStateListener(this);
    }

    @Override
    public void start() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PersistedDataHost
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void readPersisted() {
        if (BisqEnvironment.isDAOActivatedAndBaseCurrencySupportingBsq()) {
            MyBlindVoteList persisted = storage.initAndGetPersisted(myBlindVoteList, 100);
            if (persisted != null) {
                myBlindVoteList.clear();
                myBlindVoteList.addAll(persisted.getList());
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BsqStateListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onNewBlockHeight(int blockHeight) {
    }

    @Override
    public void onParseTxsComplete(Block block) {
    }

    @Override
    public void onParseBlockChainComplete() {
        rePublishOnceWellConnected();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Tuple2<Coin, Integer> getMiningFeeAndTxSize(Coin stake)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        // We set dummy opReturn data
        Coin blindVoteFee = BlindVoteConsensus.getFee(bsqStateService, bsqStateService.getChainHeight());
        Transaction dummyTx = getBlindVoteTx(stake, blindVoteFee, new byte[22]);
        Coin miningFee = dummyTx.getFee();
        int txSize = dummyTx.bitcoinSerialize().length;
        return new Tuple2<>(miningFee, txSize);
    }

    public void publishBlindVote(Coin stake, ResultHandler resultHandler, ExceptionHandler exceptionHandler) {
        try {
            SecretKey secretKey = BlindVoteConsensus.getSecretKey();
            BallotList sortedBallotList = BlindVoteConsensus.getSortedBallotList(ballotListService);
            byte[] encryptedVotes = getEncryptedVotes(sortedBallotList, secretKey);
            byte[] opReturnData = getOpReturnData(encryptedVotes);
            Coin blindVoteFee = BlindVoteConsensus.getFee(bsqStateService, bsqStateService.getChainHeight());
            Transaction blindVoteTx = getBlindVoteTx(stake, blindVoteFee, opReturnData);
            String blindVoteTxId = blindVoteTx.getHashAsString();

            //TODO move at end of method?
            publishTx(resultHandler, exceptionHandler, blindVoteTx);

            byte[] encryptedMeritList = getEncryptedMeritList(blindVoteTxId, secretKey);

            // We prefer to not wait for the tx broadcast as if the tx broadcast would fail we still prefer to have our
            // blind vote stored and broadcasted to the p2p network. The tx might get re-broadcasted at a restart and
            // in worst case if it does not succeed the blind vote will be ignored anyway.
            // Inconsistently propagated blind votes in the p2p network could have potentially worse effects.
            BlindVote blindVote = new BlindVote(encryptedVotes, blindVoteTxId, stake.value, encryptedMeritList);
            addBlindVoteToList(blindVote);

            addToP2PNetwork(blindVote, errorMessage -> {
                log.error(errorMessage);
                //TODO define specific exception
                exceptionHandler.handleException(new Exception(errorMessage));
            });

            // We store our source data for the blind vote in myVoteList
            myVoteListService.createAndAddMyVote(sortedBallotList, secretKey, blindVote);
        } catch (CryptoException | TransactionVerificationException | InsufficientMoneyException |
                WalletException | IOException exception) {
            exceptionHandler.handleException(exception);
        }
    }

    public long getCurrentlyAvailableMerit() {
        MeritList meritList = getMerits(null);
        return VoteResultConsensus.getCurrentlyAvailableMerit(meritList, bsqStateService.getChainHeight());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////


    private byte[] getEncryptedVotes(BallotList sortedBallotList, SecretKey secretKey) throws CryptoException {
        // We don't want to store the proposal but only use the proposalTxId as reference in our encrypted list.
        // So we convert it to the VoteWithProposalTxIdList.
        // The VoteWithProposalTxIdList is used for serialisation with protobuffer, it is not actually persisted but we
        // use the PersistableList base class for convenience.
        final List<VoteWithProposalTxId> list = sortedBallotList.stream()
                .map(ballot -> new VoteWithProposalTxId(ballot.getTxId(), ballot.getVote()))
                .collect(Collectors.toList());
        final VoteWithProposalTxIdList voteWithProposalTxIdList = new VoteWithProposalTxIdList(list);
        log.info("voteWithProposalTxIdList used in blind vote. voteWithProposalTxIdList={}", voteWithProposalTxIdList);
        return BlindVoteConsensus.getEncryptedVotes(voteWithProposalTxIdList, secretKey);
    }

    private byte[] getOpReturnData(byte[] encryptedVotes) throws IOException {
        // We cannot use hash of whole blindVote data because we create the merit signature with the blindVoteTxId
        // So we use the encryptedVotes for the hash only.
        final byte[] hash = BlindVoteConsensus.getHashOfEncryptedProposalList(encryptedVotes);
        log.info("Sha256Ripemd160 hash of encryptedVotes: " + Utilities.bytesAsHexString(hash));
        return BlindVoteConsensus.getOpReturnData(hash);
    }

    private byte[] getEncryptedMeritList(String blindVoteTxId, SecretKey secretKey) throws CryptoException {
        MeritList meritList = getMerits(blindVoteTxId);
        return BlindVoteConsensus.getEncryptedMeritList(meritList, secretKey);
    }

    // blindVoteTxId is null if we use the method from the getCurrentlyAvailableMerit call.
    public MeritList getMerits(@Nullable String blindVoteTxId) {
        // Create a lookup set for txIds of own comp. requests
        Set<String> myCompensationProposalTxIs = myProposalListService.getList().stream()
                .filter(proposal -> proposal instanceof CompensationProposal)
                .map(Proposal::getTxId)
                .collect(Collectors.toSet());

        return new MeritList(bsqStateService.getIssuanceSet().stream()
                .map(issuance -> {
                    // We check if it is our proposal
                    if (!myCompensationProposalTxIs.contains(issuance.getTxId()))
                        return null;

                    byte[] signatureAsBytes;
                    if (blindVoteTxId != null) {
                        String pubKey = issuance.getPubKey();
                        if (pubKey == null) {
                            log.error("We did not have a pubKey in our issuance object. " +
                                    "txId={}, issuance={}", issuance.getTxId(), issuance);
                            return null;
                        }

                        DeterministicKey key = bsqWalletService.findKeyFromPubKey(Utilities.decodeFromHex(pubKey));
                        if (key == null) {
                            log.error("We did not find the key for our compensation request. txId={}",
                                    issuance.getTxId());
                            return null;
                        }

                        // We sign the txId so we be sure that the signature could not be used by anyone else
                        // In the verification the txId will be checked as well.
                        ECKey.ECDSASignature signature = key.sign(Sha256Hash.wrap(blindVoteTxId));
                        signatureAsBytes = signature.toCanonicalised().encodeToDER();
                    } else {
                        // In case we use it for requesting the currently available merit we don't apply a signature
                        signatureAsBytes = new byte[0];
                    }
                    return new Merit(issuance, signatureAsBytes);

                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private void publishTx(ResultHandler resultHandler, ExceptionHandler exceptionHandler, Transaction blindVoteTx) {
        log.info("blindVoteTx={}", blindVoteTx.toString());
        walletsManager.publishAndCommitBsqTx(blindVoteTx, new TxBroadcaster.Callback() {
            @Override
            public void onSuccess(Transaction transaction) {
                log.info("BlindVote tx published. txId={}", transaction.getHashAsString());
                resultHandler.handleResult();
            }

            @Override
            public void onTimeout(TxBroadcastTimeoutException exception) {
                // TODO handle
                // We need to handle cases where a timeout happens and
                // the tx might get broadcasted at a later restart!
                // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                exceptionHandler.handleException(exception);
            }

            @Override
            public void onTxMalleability(TxMalleabilityException exception) {
                // TODO handle
                // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                exceptionHandler.handleException(exception);
            }

            @Override
            public void onFailure(TxBroadcastException exception) {
                // TODO handle
                // We need to be sure that in case of a failed tx the locked stake gets unlocked!
                exceptionHandler.handleException(exception);
            }
        });
    }

    private Transaction getBlindVoteTx(Coin stake, Coin fee, byte[] opReturnData)
            throws InsufficientMoneyException, WalletException, TransactionVerificationException {
        Transaction preparedTx = bsqWalletService.getPreparedBlindVoteTx(fee, stake);
        Transaction txWithBtcFee = btcWalletService.completePreparedBlindVoteTx(preparedTx, opReturnData);
        return bsqWalletService.signTx(txWithBtcFee);
    }

    private void rePublishOnceWellConnected() {
        if ((p2PService.getNumConnectedPeers().get() > 4 && p2PService.isBootstrapped()) || DevEnv.isDevMode()) {
            int chainHeight = periodService.getChainHeight();
            myBlindVoteList.stream()
                    .filter(blindVote -> periodService.isTxInPhaseAndCycle(blindVote.getTxId(),
                            DaoPhase.Phase.BLIND_VOTE,
                            chainHeight))
                    .forEach(blindVote -> addToP2PNetwork(blindVote, null));

            // We delay removal of listener as we call that inside listener itself.
            UserThread.execute(() -> p2PService.getNumConnectedPeers().removeListener(numConnectedPeersListener));
        }
    }

    private void addToP2PNetwork(BlindVote blindVote, @Nullable ErrorMessageHandler errorMessageHandler) {
        BlindVotePayload blindVotePayload = new BlindVotePayload(blindVote);
        boolean success = p2PService.addPersistableNetworkPayload(blindVotePayload, true);

        if (success) {
            log.info("We added a blindVotePayload to the P2P network as append only data. blindVoteTxId={}",
                    blindVote.getTxId());
        } else {
            final String msg = "Adding of blindVotePayload to P2P network failed. blindVoteTxId=" + blindVote.getTxId();
            log.error(msg);
            if (errorMessageHandler != null)
                errorMessageHandler.handleErrorMessage(msg);
        }
    }

    private void addBlindVoteToList(BlindVote blindVote) {
        if (!myBlindVoteList.getList().contains(blindVote)) {
            myBlindVoteList.add(blindVote);
            persist();
        }
    }

    private void persist() {
        storage.queueUpForSave();
    }
}
