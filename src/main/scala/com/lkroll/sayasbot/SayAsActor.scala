package com.lkroll.sayasbot

import net.katsstuff.ackcord._
import net.katsstuff.ackcord.data._
import net.katsstuff.ackcord.http.rest._
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated }
import akka.pattern.ask
import akka.persistence._
import scala.collection.mutable

case class CharacterEntry(name: String, avatar: Option[String], url: Option[String]) {
  def asAuthor = OutgoingEmbedAuthor(
    name = name,
    url = url,
    iconUrl = avatar);
}

class SayAsActor(idO: Option[GuildId]) extends PersistentActor with ActorLogging {
  import SayAsCmd._

  private val idS = idO match {
    case Some(id) => id.toString()
    case None     => "fallback"
  };
  override def persistenceId = s"say-as-actor-$idS";

  private var characters = mutable.HashMap.empty[String, CharacterEntry];
  private var speakingAs = mutable.HashMap.empty[UserId, CharacterEntry];

  override val receiveRecover: Receive = {
    case RegisterCharacter(name, avatar, url) => {
      register(CharacterEntry(name, avatar, url))
    }
    case UnregisterCharacter(name) => {
      unregister(name)
    }
    case ClearCharacters => {
      clearAll();
    }
    case evt: SayAsMessage   => log.warning(s"Received event during recovery that should not be journaled: $evt")
    case SnapshotOffer(_, _) => () // ignore
    case RecoveryCompleted   => log.info(s"Recovery for $persistenceId is complete.")
  }

  override val receiveCommand: Receive = {
    case SayAs(author, content) => {
      lookup(author) match {
        case Some(char) => {
          val split = splitIfTooLong(content);
          val splitLength = split.length;
          val msgData = split.zipWithIndex.map {
            case (fittingContent, index) => {
              val title = if (splitLength > 1) Some(s"Part ${index + 1}/$splitLength") else None;
              CreateMessageData(embed = Some(OutgoingEmbed(
                title = title,
                author = Some(char.asAuthor),
                description = Some(fittingContent),
                timestamp = Some(java.time.OffsetDateTime.now()))))
            }
          };
          replyWith(msgData)
        }
        case None => {
          val resp = CreateMessageData(
            content = s"Could not find character for $author.");
          replyWith(List(resp))
        }
      }

    }
    case SpeakAs(author, user) => {
      lookup(author) match {
        case Some(char) => {
          speakingAs += (user -> char);
          val resp = CreateMessageData(embed = Some(OutgoingEmbed(
            author = Some(char.asAuthor),
            description = Some("You are speaking as me now."),
            timestamp = Some(java.time.OffsetDateTime.now()))));
          replyWith(List(resp))
        }
        case None => {
          val resp = CreateMessageData(
            content = s"Could not find character for $author.");
          replyWith(List(resp))
        }
      }
    }
    case SpeakAsSelf(user) => {
      val msg = speakingAs.remove(user) match {
        case Some(_) => s"You are yourself again, $user!"
        case None    => s"You've never been anyone else, $user."
      };
      val resp = CreateMessageData(
        content = msg);
      replyWith(List(resp))
    }
    case PipeFrom(text, user) => {
      speakingAs.get(user) match {
        case Some(char) => {
          val split = splitIfTooLong(text);
          val splitLength = split.length;
          val msgData = split.zipWithIndex.map {
            case (fittingContent, index) => {
              val title = if (splitLength > 1) Some(s"Part ${index + 1}/$splitLength") else None;
              CreateMessageData(embed = Some(OutgoingEmbed(
                title = title,
                author = Some(char.asAuthor),
                description = Some(fittingContent),
                timestamp = Some(java.time.OffsetDateTime.now()))))
            }
          };
          replyWith(msgData)
        }
        case None => {
          val resp = CreateMessageData(
            content = s"You are not currently speaking as anyone, $user.");
          replyWith(List(resp))
        }
      }
    }
    case ev @ RegisterCharacter(name, _, _) => {
      if (canRegister(name)) {
        persist(ev){
          case RegisterCharacter(name, avatar, url) =>
            val char = CharacterEntry(name, avatar, url);
            register(char);
            val resp = CreateMessageData(embed = Some(OutgoingEmbed(
              author = Some(char.asAuthor),
              description = Some("I have been created."),
              timestamp = Some(java.time.OffsetDateTime.now()))));
            replyWith(List(resp))
        }
      } else {
        val resp = CreateMessageData(
          content = s"Could not create character for $name, as such an entry already exists.");
        replyWith(List(resp))
      }
    }
    case ev @ UnregisterCharacter(name) => {
      if (isRegistered(name)) {
        persist(ev) {
          case UnregisterCharacter(name) =>
            val char = unregister(name).get;
            val resp = CreateMessageData(embed = Some(OutgoingEmbed(
              author = Some(char.asAuthor),
              description = Some("Oh, well, goodbye then..."),
              timestamp = Some(java.time.OffsetDateTime.now()))));
            replyWith(List(resp))
        }
      } else {
        val resp = CreateMessageData(
          content = s"Could not remove character for $name, as no such entry exists.");
        replyWith(List(resp))
      }
    }
    case ev @ ClearCharacters => {
      persist(ev) { _ =>
        deleteMessages(lastSequenceNr);
        clearAll();
        val resp = CreateMessageData(
          content = s"All characters cleared.");
        replyWith(List(resp))
      }

    }
    case ListCharacters => {
      val content = registered.map { char =>
        val avatarS = char.avatar match {
          case Some(avatar) => s" [Avatar](${avatar})"
          case None         => ""
        };
        val profileS = char.url match {
          case Some(url) => s" [Profile](${url})"
          case None      => ""
        };
        s"- **${char.name}**$profileS$avatarS"
      } mkString ("\n");
      val split = splitIfTooLong(content);
      val splitLength = split.length;
      val msgData = split.zipWithIndex.map {
        case (fittingContent, index) => {
          val footer = if (splitLength > 1) Some(s"Part ${index + 1}/$splitLength") else None;
          CreateMessageData(embed = Some(OutgoingEmbed(
            title = Some("Registered Characters"),
            description = Some(fittingContent),
            footer = footer.map(OutgoingEmbedFooter(_)),
            timestamp = Some(java.time.OffsetDateTime.now()))))
        }
      };
      replyWith(msgData)
    }
  }

  private def clearAll(): Unit = {
    characters.clear();
    speakingAs.clear(); // can't speak as a non-existent char
  }
  private def canRegister(name: String): Boolean = !isRegistered(name);
  private def isRegistered(name: String): Boolean = characters.contains(name);
  private def register(char: CharacterEntry): Unit = {
    characters += (char.name -> char)
  }
  private def unregister(name: String): Option[CharacterEntry] = {
    val res = characters.remove(name);
    res.foreach { char =>
      speakingAs.filter(t => t._2.name == char.name).keys.foreach(speakingAs.remove);
    }
    res
  }
  private def lookup(key: String): Option[CharacterEntry] = {
    characters.get(key).orElse(firstFullPrefix(key))
  }
  private def firstFullPrefix(key: String): Option[CharacterEntry] = {
    characters.foreach {
      case (k, v) =>
        if (k.startsWith(key)) {
          return Some(v)
        }
    }
    return None;
  }

  private def replyWith(data: List[CreateMessageData]): Unit = {
    sender ! data
  }
  private def registered: Iterable[CharacterEntry] = characters.values;

  private def splitIfTooLong(s: String): List[String] = {
    val l = s.length();
    if (l < 2048) {
      List(s)
    } else {
      s.grouped(2048).toList
    }
  }
}
