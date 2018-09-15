package com.lkroll.sayasbot

import scala.language.higherKinds

import akka.NotUsed
import akka.actor.{ ActorRef, PoisonPill }
import akka.stream.scaladsl.{ Flow, Sink }
import cats.Monad
//import net.katsstuff.ackcord.{sourceMonadInstance =>, _}
import net.katsstuff.ackcord.data.UserId
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.http.rest._
import net.katsstuff.ackcord.syntax._
import net.katsstuff.ackcord.util.Streamable

class BotCommands[F[_]: Monad: Streamable] {

  val pingCmdFactory: ParsedCmdFactory[F, NotUsed, NotUsed] = ParsedCmdFactory[F, NotUsed, NotUsed](
    refiner = CmdInfo[F](
      prefix = Categories.generalCommands,
      aliases = Seq("ping")),
    sink = requests =>
      //Completely manual
      ParsedCmdFlow[F, NotUsed]
        .map(_ => cmd => CreateMessage.mkContent(cmd.msg.channelId, "Pong"))
        .to(requests.sinkIgnore),
    description =
      Some(CmdDescription(
        name = "Ping",
        description = "Ping this bot and get a response. Used for testing")));

  val killCmdFactory: ParsedCmdFactory[F, NotUsed, NotUsed] =
    ParsedCmdFactory[F, NotUsed, NotUsed](
      refiner = CmdInfo[F](
        prefix = Categories.adminCommands,
        aliases = Seq("kill", "die"),
        filters = Seq(ByUser(UserId(Main.adminId)))),
      //We use system.actorOf to keep the actor alive when this actor shuts down
      sink = requests => Sink.actorRef(
        ref = requests.system.actorOf(KillCmd.props(requests), "KillCmd"),
        onCompleteMessage = PoisonPill),
      description = Some(CmdDescription(
        name = "Kill bot",
        description = "Shut down this bot")));

  def charCmdFactory(sayAsActor: ActorRef): ParsedCmdFactory[F, SayAsCmd.CharacterArgs, NotUsed] =
    ParsedCmdFactory[F, SayAsCmd.CharacterArgs, NotUsed](
      refiner = CmdInfo[F](
        prefix = Categories.adminCommands,
        aliases = Seq("char"),
        filters = Seq(ByUser(UserId(Main.adminId)))),
      sink = _ => Sink.actorRefWithAck(
        ref = sayAsActor,
        onInitMessage = SayAsCmd.InitAck,
        ackMessage = SayAsCmd.Ack,
        onCompleteMessage = PoisonPill),
      description = Some(CmdDescription(
        name = "Character Registry",
        description = "Add/Remove/List Characters",
        usage = "register <character name> [[<char url>]] [-> <avatar url>]|unregister <character name>|list|clear")));

  def sayasCmdFactory(sayAsActor: ActorRef): ParsedCmdFactory[F, SayAsCmd.SayArgs, NotUsed] =
    ParsedCmdFactory[F, SayAsCmd.SayArgs, NotUsed](
      refiner = CmdInfo[F](
        prefix = Categories.generalCommands,
        aliases = Seq("sayas")),
      sink = _ => Sink.actorRefWithAck(
        ref = sayAsActor,
        onInitMessage = SayAsCmd.InitAck,
        ackMessage = SayAsCmd.Ack,
        onCompleteMessage = PoisonPill),
      description = Some(CmdDescription(
        name = "SayAs",
        description = "Say something as a registered character",
        usage = "<character name>: <text>|<character name>NEWLINE<text>")));

  def speakasCmdFactory(sayAsActor: ActorRef): ParsedCmdFactory[F, SayAsCmd.SpeakArgs, NotUsed] =
    ParsedCmdFactory[F, SayAsCmd.SpeakArgs, NotUsed](
      refiner = CmdInfo[F](
        prefix = Categories.generalCommands,
        aliases = Seq("speakas")),
      sink = _ => Sink.actorRefWithAck(
        ref = sayAsActor,
        onInitMessage = SayAsCmd.InitAck,
        ackMessage = SayAsCmd.Ack,
        onCompleteMessage = PoisonPill),
      description = Some(CmdDescription(
        name = "SpeakAs",
        description = "Speak as a registered character. Use pipe (|) at the beginning of a message to speak through the currently active character.",
        usage = "<character name>|self|")));

  def pipeCmdFactory(sayAsActor: ActorRef): ParsedCmdFactory[F, SayAsCmd.PipeArgs, NotUsed] =
    ParsedCmdFactory[F, SayAsCmd.PipeArgs, NotUsed](
      refiner = NoAliasRefiner(Categories.pipeCommand),
      sink = _ => Sink.actorRefWithAck(
        ref = sayAsActor,
        onInitMessage = SayAsCmd.InitAck,
        ackMessage = SayAsCmd.Ack,
        onCompleteMessage = PoisonPill),
      description = Some(CmdDescription(
        name = "Pipe Message",
        description = "Pipe your message into the bot. Used together with /speakas for a convenient alternative to /sayas",
        usage = "<message text>")));

  def registerAll(sayAsActor: ActorRef, register: ParsedCmdFactory[F, _, NotUsed] => Unit): Unit = {
    register(pingCmdFactory);
    register(killCmdFactory);
    register(charCmdFactory(sayAsActor));
    register(sayasCmdFactory(sayAsActor));
    register(speakasCmdFactory(sayAsActor));
    register(pipeCmdFactory(sayAsActor));
  }
}

case class NoAliasRefiner[F[_]: Monad](prefix: String) extends CmdRefiner[F] {
  import cats.syntax.all._
  import cats.data.EitherT
  import net.katsstuff.ackcord.CacheSnapshot
  import net.katsstuff.ackcord.data.Message

  def prefix(message: Message)(implicit c: CacheSnapshot[F]): F[String] = prefix.pure

  override def refine(raw: RawCmd[F]): EitherT[F, Option[CmdMessage[F] with CmdError[F]], Cmd[F]] = {
    implicit val cache: CacheSnapshot[F] = raw.c
    val canRun = prefix(raw.msg)
      .map(_ == raw.prefix)
      .map(b => if (b) Right(Cmd(raw.msg, raw.args, raw.c)) else Left(Option.empty[CmdMessage[F] with CmdError[F]]))

    EitherT(canRun)
  }
}
