package com.lkroll.sayasbot

import scala.language.higherKinds

import akka.NotUsed
import akka.actor.{ ActorRef, ActorLogging, ActorSystem, PoisonPill, Props }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Sink, Source }
import cats.{ Monad, Id }
import net.katsstuff.ackcord.MemoryCacheSnapshot
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.data.{ EmbedField, Message, OutgoingEmbed, OutgoingEmbedFooter }
import net.katsstuff.ackcord.data.raw.RawMessage
import net.katsstuff.ackcord.http.requests.{ Request, RequestHelper }
import net.katsstuff.ackcord.http.rest.CreateMessageData

class HelpCommand(requests: RequestHelper) extends HelpCmd with ActorLogging {

  implicit val system: ActorSystem = context.system
  import requests.mat
  import context.dispatcher

  private val msgQueue =
    Source.queue(32, OverflowStrategy.backpressure).to(requests.sinkIgnore[RawMessage, NotUsed]).run();

  private val withInit: Receive = {
    case HelpCommand.InitAck => {
      log.debug("Got Init");
      sender() ! HelpCommand.Ack
    }
    case x => {
      log.debug(s"Got $x");
      super.receive(x)
    }
  }

  override def receive: Receive = withInit //.orElse(super.receive)

  override def createSearchReply(
    message: Message,
    query:   String, matches: Seq[HelpCmd.CommandRegistration])(implicit c: MemoryCacheSnapshot): CreateMessageData = {

    CreateMessageData(
      embed = Some(
        OutgoingEmbed(
          title = Some(s"Commands matching: $query"),
          fields = matches
            .filter(_.info.filters(message).forall(_.isAllowed[Id](message)))
            .map(createContent(message, _)))))
  }

  override def createReplyAll(message: Message, page: Int)(implicit c: MemoryCacheSnapshot): CreateMessageData = {

    val commandsSlice = commands.toSeq
      .sortBy(reg => (reg.info.prefix(message), reg.info.aliases(message).head))
      .slice(page * 10, (page + 1) * 10)
    val maxPages = Math.max(commands.size / 10, 1)
    if (commandsSlice.isEmpty) {
      CreateMessageData(s"Max pages: $maxPages")
    } else {

      CreateMessageData(
        embed = Some(
          OutgoingEmbed(
            fields = commandsSlice.map(createContent(message, _)),
            footer = Some(OutgoingEmbedFooter(s"Page: ${page + 1} of $maxPages")))))
    }
  }

  def createContent(
    message: Message,
    reg:     HelpCmd.CommandRegistration)(implicit c: MemoryCacheSnapshot): EmbedField = {

    val builder = StringBuilder.newBuilder
    builder.append(s"Name: ${reg.description.name}\n")
    builder.append(s"Description: ${reg.description.description}\n")
    builder.append(
      s"Usage: ${reg.info.prefix(message)}${reg.info.aliases(message).mkString("|")} ${reg.description.usage}\n")

    EmbedField(reg.description.name, builder.mkString)
  }

  override def sendMessageAndAck(sender: ActorRef, request: Request[RawMessage, NotUsed]): Unit =
    msgQueue.offer(request).onComplete(_ => sendAck(sender))

  override def sendAck(sender: ActorRef): Unit = sender ! HelpCommand.Ack
}

object HelpCommand {
  case object InitAck
  case object Ack

  def props(requests: RequestHelper): Props = Props(new HelpCommand(requests))
}

object HelpCommandFactory {
  def apply[F[_]: Monad](helpCmdActor: ActorRef): ParsedCmdFactory[F, Option[HelpCmd.Args], NotUsed] = ParsedCmdFactory(
    refiner = CmdInfo[F](
      prefix = Categories.generalCommands,
      aliases = Seq("help")),
    sink = _ => Sink.actorRefWithAck(
      ref = helpCmdActor,
      onInitMessage = HelpCommand.InitAck,
      ackMessage = HelpCommand.Ack,
      onCompleteMessage = PoisonPill),
    description = Some(CmdDescription(
      name = "Help",
      description = "This command right here",
      usage = "<page|command>")))
}
