package chana.mq.amqp.engine

import akka.util.ByteIterator
import akka.util.ByteString
import chana.mq.amqp.model.ErrorCodes
import chana.mq.amqp.model.Frame
import java.io.EOFException
import java.nio.ByteOrder
import scala.annotation.tailrec

object FrameParser {
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  /**
   * nBytes   expected number of bytes of this state
   */
  sealed trait State { def nBytes: Int }

  case object ExpectEnd extends State { def nBytes = 1 }
  case object ExpectHeader extends State { def nBytes = 7 }
  final case class ExpectData(nBytes: Int) extends State

  sealed trait Result extends State
  final case class Ok(frame: Frame) extends Result { def nBytes = 0 }
  final case class Error(errorCode: Int, message: String) extends Result { def nBytes = 0 }

  /**
   * ByteIterator.++(that) seems will cut left, we have to define an ugly custom one
   */
  private def concat(left: ByteIterator, right: ByteIterator): ByteIterator = {
    if (right.len == 0) {
      left
    } else {
      val leftLen = left.len
      if (leftLen == 0) {
        right
      } else {
        val rightLen = right.len
        val bytes = Array.ofDim[Byte](leftLen + rightLen)
        left.copyToArray(bytes, 0, leftLen)
        right.copyToArray(bytes, leftLen, rightLen)
        ByteString(bytes).iterator
      }
    }
  }

}

/**
 * General Frame Format
 * All frames start with a 7-octet header composed of a type field (octet), a channel field (short integer) and a
 * size field (long integer):
 * 0      1         3         7                     size+7 size+8
 * +------+---------+---------+   +-------------+  +-----------+
 * | type | channel | size    |   |  payload    |  | frame-end |
 * +------+---------+---------+   +-------------+  +-----------+
 *  octet   short    long          'size' octets      octet
 *
 * AMQP defines these frame types:
 *  Type = 1, "METHOD": method frame.
 *  Type = 2, "HEADER": content header frame.
 *  Type = 3, "BODY": content body frame.
 *  Type = 4, "HEARTBEAT": heartbeat frame.
 * The channel number is 0 for all frames which are global to the connection and 1-65535 for frames that
 * refer to specific channels.
 */
final class FrameParser(messageSizeLimit: Long = Long.MaxValue) {
  import FrameParser._

  private var tpe: Byte = _
  private var channel: Int = _
  private var size: Int = _

  private var state: State = ExpectHeader
  private var input: ByteIterator = ByteString.empty.iterator
  private val payload = ByteString.newBuilder

  def onReceive(newInput: ByteIterator): Vector[Result] = {
    input = concat(input, newInput)
    process(Vector())
  }

  /**
   * Loopable method
   */
  @tailrec
  private def process(results: Vector[Result]): Vector[Result] = {
    // parse and see if we've finished a frame, add to acc and reset state
    val oldState = state
    val results1 = parse(input, state) match {
      case result: Error =>
        payload.clear
        state = ExpectHeader
        results :+ result

      case result: Ok =>
        payload.clear
        state = ExpectHeader
        results :+ result

      case s =>
        state = s
        results
    }

    // has more data? go on if true, else wait for more input
    if (input.hasNext && state != oldState) {
      process(results1)
    } else {
      results1
    }
  }

  private def parse(input: ByteIterator, state: State): State = state match {
    case ExpectHeader =>
      if (input.len < ExpectHeader.nBytes) {
        ExpectHeader
      } else {
        tpe = input.next()
        channel = readUnsignedShort(input)
        size = readInt(input)

        if (size > messageSizeLimit) {
          Error(ErrorCodes.MESSAGE_TOO_LARGE, s"Massage $size large than $messageSizeLimit")
        } else if (size == 0) {
          ExpectEnd
        } else {
          ExpectData(size)
        }
      }

    case ExpectData(n) =>
      val len = input.len

      val bs = Array.ofDim[Byte](math.min(len, n))
      input.getBytes(bs)
      payload ++= bs

      if (len < n) {
        ExpectData(n - len)
      } else {
        ExpectEnd
      }

    case ExpectEnd =>
      if (input.len < ExpectEnd.nBytes) {
        ExpectEnd
      } else {
        readUnsignedByte(input) match {
          case Frame.FRAME_END =>
            Ok(Frame(tpe, channel, payload.result.toArray))
          case x =>
            Error(ErrorCodes.FRAME_ERROR, s"Bad frame end marker $x, type=$tpe, channel=$channel, size=$size, payload length=${payload.length}")
        }
      }

    case x => x
  }

  private def readUnsignedByte(input: ByteIterator): Int = {
    val b = read(input)

    if (b < 0) throw new EOFException()

    b
  }

  private def readUnsignedShort(input: ByteIterator): Int = {
    val b1 = read(input)
    val b2 = read(input)

    if ((b1 | b2) < 0) throw new EOFException()

    (b1 << 8) + (b2 << 0)
  }

  private def readInt(input: ByteIterator): Int = {
    val b1 = read(input)
    val b2 = read(input)
    val b3 = read(input)
    val b4 = read(input)

    if ((b1 | b2 | b3 | b4) < 0) throw new EOFException()

    ((b1 << 24) + (b2 << 16) + (b3 << 8) + (b4 << 0))
  }

  private def read(input: ByteIterator): Int = {
    if (input.hasNext) {
      input.next().toInt & 0xff
    } else {
      -1
    }
  }
}