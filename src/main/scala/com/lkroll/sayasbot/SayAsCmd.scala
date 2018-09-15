package com.lkroll.sayasbot

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated }
import akka.stream._
import akka.stream.scaladsl._
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout
import cats.{ Id, Monad }
import cats.data.EitherT
import net.katsstuff.ackcord._
import net.katsstuff.ackcord.data._
import net.katsstuff.ackcord.data.raw.RawMessage
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.http.requests.{ Request, RequestHelper }
import net.katsstuff.ackcord.http.rest._
import scala.concurrent.duration._
import scala.collection.mutable

class SayAsCmd(requests: RequestHelper) extends Actor with ActorLogging {
  import SayAsCmd._

  implicit val askTimeout = Timeout(5.seconds)
  implicit val system: ActorSystem = context.system;
  import requests.mat;
  import context.dispatcher;

  //  private val messageGraph = Sink.fromGraph(GraphDSL.create() { implicit builder =>
  //    import GraphDSL.Implicits._;
  //
  //    val unzip = builder.add(Unzip[List[CreateMessage[NotUsed]], DeleteMessage[NotUsed]]);
  //
  //    val msgSink = requests.sinkIgnore[RawMessage, NotUsed];
  //    val deleteSink = requests.sinkIgnore[NotUsed, NotUsed];
  //
  //    unzip.out1.mapConcat(dm => List(dm)).log("DELETE MSG", dm => dm.messageId) ~> deleteSink;
  //    unzip.out0.mapConcat(identity).log("REPLY MSG", cm => cm.bodyForLogging) ~> msgSink;
  //    SinkShape.of(unzip.in)
  //  });

  //  private val deleteGraph = Sink.fromGraph(GraphDSL.create() {implicit builder =>
  //    val filter = Flow[(DeleteMessage[NotUsed], List[CreateMessage[NotUsed]])].map(t => t._1);
  //    SinkShape.of(filter.shape.in)
  //  });

  private val msgQueue = Source.queue[(ActorRef, SayAsMessage, ChannelId, MessageId)](32, OverflowStrategy.backpressure)
    .mapAsyncUnordered(parallelism = 1000)((t: (ActorRef, SayAsMessage, ChannelId, MessageId)) =>
      (t._1 ? t._2).mapTo[List[CreateMessageData]].map(l => (l.map(CreateMessage(t._3, _))))) //DeleteMessage(t._3, t._4) ::
    .mapConcat(identity)
    .to(requests.sinkIgnore[Any, NotUsed]).run();

  private val serverHandlers = mutable.Map.empty[GuildId, ActorRef];
  private val fallbackHandler = context.actorOf(Props(new SayAsActor(None)));

  override def receive: Receive = {
    case InitAck => {
      log.debug("Got Init");
      sendAck(sender);
    }
    case ParsedCmd(msg, cmd @ CharacterArgs.Register(name, avatar, url), _, c) => {
      log.debug(s"Received register command: $cmd");
      routeToHandler(msg, RegisterCharacter(name, avatar, url))(c);
    }
    case ParsedCmd(msg, cmd @ CharacterArgs.Unregister(name), _, c) => {
      log.debug(s"Received unregister command: $cmd");
      routeToHandler(msg, UnregisterCharacter(name))(c);
    }
    case ParsedCmd(msg, cmd @ CharacterArgs.ListAll, _, c) => {
      log.debug(s"Received list command: $cmd");
      routeToHandler(msg, ListCharacters)(c);
    }
    case ParsedCmd(msg, cmd @ CharacterArgs.ClearAll, _, c) => {
      log.debug(s"Received clear command: $cmd");
      routeToHandler(msg, ClearCharacters)(c);
    }
    case ParsedCmd(msg, cmd @ SayArgs.Say(author, content), _, c) => {
      log.debug(s"Received sayas command: $cmd");
      routeToHandler(msg, SayAs(author, content))(c);
    }
    case ParsedCmd(msg, cmd @ SpeakArgs.Speak(author), _, c) => {
      msg.authorUserId match {
        case Some(userId) => {
          log.debug(s"Received speakas command from ${}: $cmd");
          routeToHandler(msg, SpeakAs(author, userId))(c);
        }
        case None => log.error(s"Could not find UserId for author=${msg.authorId}")
      }
    }
    case ParsedCmd(msg, cmd @ SpeakArgs.SpeakSelf, _, c) => {
      msg.authorUserId match {
        case Some(userId) => {
          log.debug(s"Received speakas self command from $userId: $cmd");
          routeToHandler(msg, SpeakAsSelf(userId))(c);
        }
        case None => log.error(s"Could not find UserId for author=${msg.authorId}")
      }
    }
    case ParsedCmd(msg, cmd @ PipeArgs.Pipe(text), _, c) => {
      msg.authorUserId match {
        case Some(userId) => {
          log.debug(s"Received pipe command from $userId: $cmd");
          routeToHandler(msg, PipeFrom(text, userId))(c);
        }
        case None => log.error(s"Could not find UserId for author=${msg.authorId}")
      }
    }
    case x => {
      log.warning(s"Received unexpected message: $x");
      sendAck(sender);
    }
  }

  def routeToHandler(orig: Message, msg: SayAsMessage)(implicit c: CacheSnapshot[Any]): Unit = {
    implicit val cs = c.asInstanceOf[CacheSnapshot[Id]]; // easier than pattern matching some applicative type in the receive

    val channelO = orig.channelId.resolve[Id];
    val handler = channelO.map {
      case guildChannel: net.katsstuff.ackcord.data.GetGuild => {
        val gId = guildChannel.guildId;
        serverHandlers.getOrElseUpdate(
          gId,
          context.actorOf(Props(new SayAsActor(Some(gId)))))
      }
      case _ => fallbackHandler
    } getOrElse (fallbackHandler);
    val msgSender = sender();
    msgQueue.offer((handler, msg, orig.channelId, orig.id)).onComplete(_ => {
      sendAck(msgSender);
      requests.singleIgnore(DeleteMessage(orig.channelId, orig.id));
    })
  }
  def sendAck(sender: ActorRef): Unit = sender ! SayAsCmd.Ack;
}
object SayAsCmd {
  import fastparse.all._

  lazy val ws = P(CharsWhileIn(" \t"));
  lazy val registerParser = P(ws.? ~ "register" ~/ ws ~ nameParser ~ ws.? ~ ("(" ~ urlParser ~ ")" ~ ws.?).? ~ ("->" ~/ ws ~ urlParser).? ~ ws.? ~ End);
  lazy val unregisterParser = P(ws.? ~ "unregister" ~/ ws ~ nameParser ~ ws.? ~ End);
  lazy val listParser = P(ws.? ~ "list" ~/ ws.? ~ End);
  lazy val clearParser = P(ws.? ~ "clear" ~/ ws.? ~ End);
  lazy val nameParser = P((allowedBlock ~ ((" " | "-" ~ !">").? ~ allowedBlock).rep.?).!);
  lazy val allowedBlock = P(CharsWhile(c => !"\n,\t-:; [](){}<>".contains(c)));
  lazy val urlParser = P(("http" ~ "s".? ~ "://" ~/ CharsWhile(c => !(c.isWhitespace || c.isControl || ":[](){}<>".contains(c)))).!);

  lazy val sayasParser = P(ws.? ~ nameParser ~ (":" | "\n" | "\r\n") ~/ AnyChar.rep.! ~ End);
  lazy val selfParser = P(ws.? ~ "self".? ~/ ws.? ~ End);

  trait CharacterArgs;
  object CharacterArgs {
    case class Register(name: String, avatar: Option[String], url: Option[String]) extends CharacterArgs
    case class Unregister(name: String) extends CharacterArgs
    case object ListAll extends CharacterArgs
    case object ClearAll extends CharacterArgs

    implicit val parser: MessageParser[CharacterArgs] = new MessageParser[CharacterArgs] {
      override def parse[F[_]](
        strings: List[String])(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, (List[String], CharacterArgs)] = {
        val input = strings.mkString(" ");
        input match {
          case registerParser(name, url, avatar) => EitherT.rightT(Nil, Register(name, avatar, url))
          case unregisterParser(name)            => EitherT.rightT(Nil, Unregister(name))
          case listParser(_)                     => EitherT.rightT(Nil, ListAll)
          case clearParser(_)                    => EitherT.rightT(Nil, ClearAll)
          case _ => {
            val Parsed.Failure(expected, failIndex, extra) = registerParser.parse(input);
            println(s"Invalid args. Expected $expected at $failIndex in '$input'.\n${extra.traced.trace}");
            EitherT.leftT("Invalid arguments. Use '/help !char' for usage information.")
          }
        }
      }
    }
  }

  trait SayArgs;
  object SayArgs {
    case class Say(character: String, content: String) extends SayArgs

    implicit val parser: MessageParser[SayArgs] = new MessageParser[SayArgs] {
      override def parse[F[_]](
        strings: List[String])(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, (List[String], SayArgs)] = {
        val input = strings.mkString(" ");
        input match {
          case sayasParser(name, content) => EitherT.rightT(Nil, Say(name, content))
          case _                          => EitherT.leftT("Not enough arguments. Use '/help /sayas' for usage information.")
        }
      }
    }
  }

  trait SpeakArgs;
  object SpeakArgs {
    case class Speak(character: String) extends SpeakArgs
    case object SpeakSelf extends SpeakArgs

    implicit val parser: MessageParser[SpeakArgs] = new MessageParser[SpeakArgs] {
      override def parse[F[_]](
        strings: List[String])(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, (List[String], SpeakArgs)] = {
        val input = strings.mkString(" ");
        input match {
          case selfParser(_)    => EitherT.rightT(Nil, SpeakSelf)
          case nameParser(name) => EitherT.rightT(Nil, Speak(name))
          case _                => EitherT.leftT("Not enough arguments. Use '/help /speakas' for usage information.")
        }
      }
    }
  }

  trait PipeArgs;
  object PipeArgs {
    case class Pipe(text: String) extends PipeArgs

    implicit val parser: MessageParser[PipeArgs] = new MessageParser[PipeArgs] {
      override def parse[F[_]](
        strings: List[String])(implicit c: CacheSnapshot[F], F: Monad[F]): EitherT[F, String, (List[String], PipeArgs)] = {
        val input = strings.mkString(" ");
        EitherT.rightT(Nil, Pipe(input))
      }
    }
  }

  case object InitAck
  case object Ack

  trait SayAsMessage
  case class RegisterCharacter(name: String, avatar: Option[String] = None, url: Option[String] = None) extends SayAsMessage
  case class UnregisterCharacter(name: String) extends SayAsMessage
  case object ListCharacters extends SayAsMessage
  case object ClearCharacters extends SayAsMessage
  case class SayAs(character: String, content: String) extends SayAsMessage
  case class SpeakAs(character: String, user: UserId) extends SayAsMessage
  case class SpeakAsSelf(user: UserId) extends SayAsMessage
  case class PipeFrom(text: String, user: UserId) extends SayAsMessage

  def props(requests: RequestHelper): Props = Props(new SayAsCmd(requests));
}
