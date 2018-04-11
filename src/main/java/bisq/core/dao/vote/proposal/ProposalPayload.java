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

package bisq.core.dao.vote.proposal;

import bisq.core.dao.blockchain.vo.Tx;
import bisq.core.dao.blockchain.vo.TxOutput;
import bisq.core.dao.blockchain.vo.TxOutputType;
import bisq.core.dao.blockchain.vo.TxType;
import bisq.core.dao.param.DaoParam;
import bisq.core.dao.vote.PeriodService;
import bisq.core.dao.vote.ValidationCandidate;
import bisq.core.dao.vote.VoteConsensusCritical;
import bisq.core.dao.vote.proposal.compensation.CompensationRequestPayload;
import bisq.core.dao.vote.proposal.generic.GenericProposalPayload;

import bisq.network.p2p.storage.payload.CapabilityRequiringPayload;
import bisq.network.p2p.storage.payload.LazyProcessedPayload;
import bisq.network.p2p.storage.payload.ProtectedStoragePayload;

import bisq.common.app.Capabilities;
import bisq.common.crypto.Sig;
import bisq.common.proto.ProtobufferException;
import bisq.common.proto.persistable.PersistablePayload;
import bisq.common.util.Utilities;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import java.security.PublicKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.Validate.notEmpty;

/**
 * Payload is sent over wire as well as it gets persisted.
 * <p>
 * We persist all ProposalPayload data in PersistableNetworkPayloadMap.
 * Data size on disk for one item is: about 743 bytes (443 bytes is for ownerPubKeyEncoded)
 * As there are not 1000s of proposals we consider that acceptable.
 */
@Slf4j
@Getter
@EqualsAndHashCode
public abstract class ProposalPayload implements LazyProcessedPayload, ProtectedStoragePayload, PersistablePayload,
        CapabilityRequiringPayload, ValidationCandidate, VoteConsensusCritical {

    protected final String uid;
    protected final String name;
    protected final String title;
    protected final String description;
    protected final String link;
    protected byte[] ownerPubKeyEncoded;
    @Setter
    @Nullable
    protected String txId;

    protected final byte version;
    protected final long creationDate;

    // Should be only used in emergency case if we need to add data but do not want to break backward compatibility
    // at the P2P network storage checks. The hash of the object will be used to verify if the data is valid. Any new
    // field in a class would break that hash and therefore break the storage mechanism.
    @Nullable
    protected Map<String, String> extraDataMap;

    // Used just for caching
    @Nullable
    private transient PublicKey ownerPubKey;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected ProposalPayload(String uid,
                              String name,
                              String title,
                              String description,
                              String link,
                              byte[] ownerPubPubKeyEncoded,
                              byte version,
                              long creationDate,
                              @Nullable String txId,
                              @Nullable Map<String, String> extraDataMap) {
        this.uid = uid;
        this.name = name;
        this.title = title;
        this.description = description;
        this.link = link;
        this.ownerPubKeyEncoded = ownerPubPubKeyEncoded;
        this.version = version;
        this.creationDate = creationDate;
        this.txId = txId;
        this.extraDataMap = extraDataMap;
    }

    public PB.ProposalPayload.Builder getPayloadBuilder() {
        final PB.ProposalPayload.Builder builder = PB.ProposalPayload.newBuilder()
                .setUid(uid)
                .setName(name)
                .setTitle(title)
                .setDescription(description)
                .setLink(link)
                .setOwnerPubKeyEncoded(ByteString.copyFrom(ownerPubKeyEncoded))
                .setVersion(version)
                .setCreationDate(creationDate);
        Optional.ofNullable(txId).ifPresent(builder::setTxId);
        Optional.ofNullable(extraDataMap).ifPresent(builder::putAllExtraData);
        return builder;
    }

    @Override
    public PB.StoragePayload toProtoMessage() {
        return PB.StoragePayload.newBuilder().setProposalPayload(getPayloadBuilder()).build();
    }

    //TODO add other proposal types
    public static ProposalPayload fromProto(PB.ProposalPayload proto) {
        switch (proto.getMessageCase()) {
            case COMPENSATION_REQUEST_PAYLOAD:
                return CompensationRequestPayload.fromProto(proto);
            case GENERIC_PROPOSAL_PAYLOAD:
                return GenericProposalPayload.fromProto(proto);
            default:
                throw new ProtobufferException("Unknown message case: " + proto.getMessageCase());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public PublicKey getOwnerPubKey() {
        if (ownerPubKey == null)
            ownerPubKey = Sig.getPublicKeyFromBytes(ownerPubKeyEncoded);

        return ownerPubKey;
    }

    // Pre 0.6 version don't know the new message type and throw an error which leads to disconnecting the peer.
    @Override
    public List<Integer> getRequiredCapabilities() {
        return new ArrayList<>(Collections.singletonList(
                Capabilities.Capability.PROPOSAL.ordinal()
        ));
    }

    protected ProposalPayload getCloneWithoutTxId() {
        ProposalPayload clone = ProposalPayload.fromProto(toProtoMessage().getProposalPayload());
        clone.setTxId(null);
        return clone;
    }

    public void validate(Tx tx, PeriodService periodService) throws ValidationException {
        validateCorrectTxType(tx);
        validateCorrectTxOutputType(tx);
        validatePhase(tx.getBlockHeight(), periodService);
        validateDataFields();
        validateHashOfOpReturnData(tx);
    }

    @Override
    public void validateCorrectTxType(Tx tx) throws ValidationException {
        try {
            checkArgument(tx.getTxType() == TxType.PROPOSAL,
                    "ProposalPayload has wrong txType. txType=" + tx.getTxType());
        } catch (Throwable e) {
            log.warn(e.toString());
            throw new ValidationException(e, tx);
        }
    }

    @Override
    public void validateCorrectTxOutputType(Tx tx) throws ValidationException {
        try {
            final TxOutput lastOutput = tx.getLastOutput();
            checkArgument(lastOutput.getTxOutputType() == TxOutputType.PROPOSAL_OP_RETURN_OUTPUT,
                    "Last output of tx has wrong txOutputType: txOutputType=" + lastOutput.getTxOutputType());
        } catch (Throwable e) {
            log.warn(e.toString());
            throw new ValidationException(e, tx);
        }
    }

    @Override
    public void validatePhase(int txBlockHeight, PeriodService periodService) throws ValidationException {
        try {
            checkArgument(periodService.isInPhase(txBlockHeight, PeriodService.Phase.PROPOSAL),
                    "Tx is not in PROPOSAL phase");
        } catch (Throwable e) {
            log.warn(e.toString());
            throw new ValidationException(e);
        }
    }

    @Override
    public void validateDataFields() throws ValidationException {
        try {
            notEmpty(name, "name must not be empty");
            notEmpty(title, "title must not be empty");
            notEmpty(description, "description must not be empty");
            notEmpty(link, "link must not be empty");

            checkArgument(ProposalConsensus.isDescriptionSizeValid(description), "description is too long");
        } catch (Throwable throwable) {
            throw new ValidationException(throwable);
        }
    }


    // We do not verify type or version as that gets verified in parser. Version might have been changed as well
    // so we don't want to fail in that case.
    @Override
    public void validateHashOfOpReturnData(Tx tx) throws ValidationException {
        try {
            byte[] txOpReturnData = tx.getTxOutput(tx.getOutputs().size() - 1).get().getOpReturnData();
            checkNotNull(txOpReturnData, "txOpReturnData must not be null");
            byte[] txHashOfPayload = Arrays.copyOfRange(txOpReturnData, 2, 22);
            // We need to set txId to null in clone to get same hash as used in the tx return data
            byte[] hash = ProposalConsensus.getHashOfPayload(getCloneWithoutTxId());
            checkArgument(Arrays.equals(txHashOfPayload, hash),
                    "OpReturn data from proposal tx is not matching the one created from the payload." +
                            "\ntxHashOfPayload=" + Utilities.encodeToHex(txHashOfPayload) +
                            "\nhash=" + Utilities.encodeToHex(hash));
        } catch (Throwable e) {
            log.debug("OpReturnData validation of proposalPayload failed. proposalPayload={}, tx={}", this, tx);
            throw new ValidationException(e, tx);
        }
    }

    @Override
    public void validateCycle(int txBlockHeight, int currentChainHeight, PeriodService periodService) throws ValidationException {
        try {
            checkArgument(periodService.isTxInCorrectCycle(txBlockHeight, currentChainHeight),
                    "Tx is not in current cycle");
        } catch (Throwable e) {
            log.warn(e.toString());
            throw new ValidationException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Date getCreationDate() {
        return new Date(creationDate);
    }

    public String getShortId() {
        return uid.substring(0, 8);
    }

    public abstract ProposalType getType();

    public abstract DaoParam getQuorumDaoParam();

    public abstract DaoParam getThresholdDaoParam();

    @Override
    public String toString() {
        return "ProposalPayload{" +
                "\n     uid='" + uid + '\'' +
                ",\n     name='" + name + '\'' +
                ",\n     title='" + title + '\'' +
                ",\n     description='" + description + '\'' +
                ",\n     link='" + link + '\'' +
                ",\n     ownerPubKeyEncoded=" + Utilities.bytesAsHexString(ownerPubKeyEncoded) +
                ",\n     txId='" + txId + '\'' +
                ",\n     version=" + version +
                ",\n     creationDate=" + new Date(creationDate) +
                ",\n     extraDataMap=" + extraDataMap +
                "\n}";
    }
}
