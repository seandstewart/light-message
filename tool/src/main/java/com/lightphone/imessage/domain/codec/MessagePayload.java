package com.lightphone.imessage.domain.codec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a complete iMessage payload to be encrypted in an envelope.
 * Contains message content, metadata, and optional attachments.
 */
public class MessagePayload {
    public final String messageId;
    public final String sender;
    public final List<String> recipients;
    public final long timestamp;
    public final String body;
    public final Map<String, String> metadata;
    public final List<AttachmentInfo> attachments;

    public MessagePayload(
            String messageId,
            String sender,
            List<String> recipients,
            long timestamp,
            String body,
            Map<String, String> metadata,
            List<AttachmentInfo> attachments
    ) {
        this.messageId = messageId;
        this.sender = sender;
        this.recipients = recipients;
        this.timestamp = timestamp;
        this.body = body;
        this.metadata = metadata != null ? metadata : new HashMap<>();
        this.attachments = attachments != null ? attachments : new ArrayList<>();
    }

    public MessagePayload(
            String messageId,
            String sender,
            List<String> recipients,
            long timestamp,
            String body
    ) {
        this(messageId, sender, recipients, timestamp, body, new HashMap<>(), new ArrayList<>());
    }

    @Override
    public String toString() {
        return "MessagePayload{" +
                "messageId='" + messageId + '\'' +
                ", sender='" + sender + '\'' +
                ", recipients=" + recipients +
                ", timestamp=" + timestamp +
                ", body='" + body + '\'' +
                ", metadata=" + metadata +
                ", attachments=" + attachments +
                '}';
    }

    /**
     * Represents an attachment reference within a message payload.
     * Contains encryption details for the attachment URL.
     */
    public static class AttachmentInfo {
        public final String id;
        public final String mimeType;
        public final String url;
        public final long size;
        public final byte[] encryptionKey;

        public AttachmentInfo(
                String id,
                String mimeType,
                String url,
                long size,
                byte[] encryptionKey
        ) {
            this.id = id;
            this.mimeType = mimeType;
            this.url = url;
            this.size = size;
            this.encryptionKey = encryptionKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AttachmentInfo)) return false;
            AttachmentInfo that = (AttachmentInfo) o;
            return size == that.size &&
                    id.equals(that.id) &&
                    mimeType.equals(that.mimeType) &&
                    url.equals(that.url) &&
                    Arrays.equals(encryptionKey, that.encryptionKey);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + mimeType.hashCode();
            result = 31 * result + url.hashCode();
            result = 31 * result + (int) (size ^ (size >>> 32));
            result = 31 * result + Arrays.hashCode(encryptionKey);
            return result;
        }

        @Override
        public String toString() {
            return "AttachmentInfo{" +
                    "id='" + id + '\'' +
                    ", mimeType='" + mimeType + '\'' +
                    ", url='" + url + '\'' +
                    ", size=" + size +
                    ", encryptionKey=" + Arrays.toString(encryptionKey) +
                    '}';
        }
    }
}
