/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.governance.blindvote;

import bisq.core.dao.governance.ballot.Ballot;
import bisq.core.dao.governance.ballot.BallotList;
import bisq.core.dao.governance.ballot.BallotListService;
import bisq.core.dao.governance.merit.MeritList;
import bisq.core.dao.state.BsqStateService;
import bisq.core.dao.state.blockchain.OpReturnType;
import bisq.core.dao.state.governance.Param;

import bisq.common.app.Version;
import bisq.common.crypto.CryptoException;
import bisq.common.crypto.Encryption;
import bisq.common.crypto.Hash;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Coin;

import javax.crypto.SecretKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * All consensus critical aspects are handled here.
 */
@Slf4j
public class BlindVoteConsensus {
    public static BallotList getSortedBallotList(BallotListService ballotListService) {
        final List<Ballot> ballotList = ballotListService.getBallotList().stream()
                .sorted(Comparator.comparing(Ballot::getTxId))
                .collect(Collectors.toList());
        log.info("Sorted ballotList: " + ballotList);
        return new BallotList(ballotList);
    }

    public static List<BlindVote> getSortedBlindVoteListOfCycle(BlindVoteService blindVoteService) {
        final List<BlindVote> list = blindVoteService.getBlindVotesInPhaseAndCycle().stream()
                .sorted(Comparator.comparing(BlindVote::getTxId))
                .collect(Collectors.toList());
        log.info("Sorted blindVote txId list: " + list.stream()
                .map(BlindVote::getTxId)
                .collect(Collectors.toList()));
        return list;
    }

    // 128 bit AES key is good enough for our use case
    public static SecretKey getSecretKey() {
        return Encryption.generateSecretKey(128);
    }

    public static byte[] getEncryptedVotes(VoteWithProposalTxIdList voteWithProposalTxIdList, SecretKey secretKey) throws CryptoException {
        final byte[] bytes = voteWithProposalTxIdList.toProtoMessage().toByteArray();
        final byte[] encrypted = Encryption.encrypt(bytes, secretKey);
        log.info("EncryptedVotes: " + Utilities.bytesAsHexString(encrypted));
        return encrypted;
    }

    public static byte[] getEncryptedMeritList(MeritList meritList, SecretKey secretKey) throws CryptoException {
        final byte[] bytes = meritList.toProtoMessage().toByteArray();
        return Encryption.encrypt(bytes, secretKey);
    }

    public static byte[] getHashOfEncryptedProposalList(byte[] encryptedProposalList) {
        return Hash.getSha256Ripemd160hash(encryptedProposalList);
    }

    public static byte[] getOpReturnData(byte[] hash) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            outputStream.write(OpReturnType.BLIND_VOTE.getType());
            outputStream.write(Version.BLIND_VOTE);
            outputStream.write(hash);
            final byte[] bytes = outputStream.toByteArray();
            log.info("OpReturnData: " + Utilities.bytesAsHexString(bytes));
            return bytes;
        } catch (IOException e) {
            // Not expected to happen ever
            e.printStackTrace();
            log.error(e.toString());
            throw e;
        }
    }

    public static Coin getFee(BsqStateService bsqStateService, int chainHeadHeight) {
        final Coin fee = Coin.valueOf(bsqStateService.getParamValue(Param.BLIND_VOTE_FEE, chainHeadHeight));
        log.info("Fee for blind vote: " + fee);
        return fee;
    }
}
