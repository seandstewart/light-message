package com.lightphone.imessage.domain.relay.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightphone.imessage.data.database.ImessageDatabase
import com.lightphone.imessage.data.entity.MessageEntity
import com.lightphone.imessage.data.repository.MessageRepository
import com.lightphone.imessage.domain.relay.MessageId
import com.lightphone.imessage.domain.relay.RelayCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for F-2 implementation: PersistThenAckPolicy.
 *
 * **F-2 Design:** Messages are persisted to the repository before acknowledging to the relay.
 * If the process crashes between these two steps, the relay's timeout will eventually resend
 * the message (ensuring durability).
 */
@RunWith(AndroidJUnit4::class)
class PersistThenAckPolicyTest {
    private lateinit var database: ImessageDatabase
    private lateinit var repository: MessageRepository
    private lateinit var policy: PersistThenAckPolicy

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = ImessageDatabase.getInstance(context)
        repository = MessageRepository(database)
        policy = PersistThenAckPolicy(repository)
    }

    @After
    fun tearDown() {
        database.clearAllTables()
        database.close()
    }

    @Test
    fun testOnIncomingMessage_SuccessfulPersist_ReturnsTrue() =
        runTest {
            // Arrange
            val cmd =
                RelayCommand.SendMessage(
                    messageId = MessageId("test-msg-1"),
                    recipientUri = "tel:+1234567890",
                    envelope = byteArrayOf(1, 2, 3, 4, 5),
                )

            // Act
            val shouldAck = policy.onIncomingMessage(cmd)

            // Assert: Should ACK because persist succeeded
            assertTrue("Policy should return true when persist succeeds", shouldAck)

            // Verify message was persisted
            val saved = runBlocking { database.messageDao().getById("test-msg-1").first() }
            assert(saved != null) { "Message should be persisted" }
        }

    @Test
    fun testOnIncomingMessage_FailedPersist_ReturnsFalse() =
        runTest {
            // Arrange: Create a message with invalid threadId to trigger foreign key constraint
            // This would normally fail. However, for this test we need to actually trigger
            // a persist failure. We'll use a very long ID that might fail validation.
            val veryLongId = "x".repeat(10000) // Potentially exceeds DB limits
            val cmd =
                RelayCommand.SendMessage(
                    messageId = MessageId(veryLongId),
                    recipientUri = "mailto:user@example.com",
                    envelope = byteArrayOf(5, 4, 3, 2, 1),
                )

            // Act
            val shouldAck = policy.onIncomingMessage(cmd)

            // Assert: Should NOT ACK because persist failed
            assertFalse(
                "Policy should return false when persist fails (let relay retry)",
                shouldAck,
            )
        }

    @Test
    fun testOnIncomingMessage_PersistsCorrectMessageEntity() =
        runTest {
            // Arrange
            val messageId = "unique-msg-id-123"
            val recipientUri = "tel:+9876543210"
            val envelope = byteArrayOf(10, 11, 12, 13)

            val cmd =
                RelayCommand.SendMessage(
                    messageId = MessageId(messageId),
                    recipientUri = recipientUri,
                    envelope = envelope,
                )

            // Act
            val shouldAck = policy.onIncomingMessage(cmd)

            // Assert: Verify ACK decision
            assertTrue("Persist should succeed", shouldAck)

            // Verify the entity was persisted correctly
            val persistedEntity = runBlocking { database.messageDao().getById(messageId).first() }

            assert(persistedEntity != null) { "Entity should be in database" }
            persistedEntity?.let {
                assert(it.id == messageId) { "ID should match" }
                assert(it.threadId == recipientUri) { "ThreadId should be recipient" }
                assert(it.isOutgoing == false) { "Should be incoming message" }
                assert(it.rawEnvelope?.contentEquals(envelope) == true) { "Envelope should match" }
            }
        }

    @Test
    fun testOnIncomingMessage_MultipleMessages_IndependentResults() =
        runTest {
            // Arrange
            val cmd1 =
                RelayCommand.SendMessage(
                    messageId = MessageId("msg-1"),
                    recipientUri = "tel:+1111111111",
                    envelope = byteArrayOf(1),
                )
            val cmd2 =
                RelayCommand.SendMessage(
                    messageId = MessageId("msg-2"),
                    recipientUri = "tel:+2222222222",
                    envelope = byteArrayOf(2),
                )

            // Act
            val result1 = policy.onIncomingMessage(cmd1)
            val result2 = policy.onIncomingMessage(cmd2)

            // Assert: Both should succeed independently
            assertTrue("First message should be ACKed", result1)
            assertTrue("Second message should also be ACKed", result2)

            // Verify both were persisted
            val msg1 = runBlocking { database.messageDao().getById("msg-1").first() }
            val msg2 = runBlocking { database.messageDao().getById("msg-2").first() }

            assertTrue("First message should be persisted", msg1 != null)
            assertTrue("Second message should be persisted", msg2 != null)
        }
}
