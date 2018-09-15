package com.lkroll.sayasbot

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import net.katsstuff.ackcord.commands.ParsedCmd
import net.katsstuff.ackcord.http.requests.{ Request, RequestHelper }
import net.katsstuff.ackcord.http.rest._

class KillCmd(requests: RequestHelper) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher;

  override def receive: Receive = {
    case ParsedCmd(msg, _, _, _) => {
      log.info("Received shutdown command");
      val reqF = requests.singleFuture(CreateMessage.mkContent(msg.channelId, "Ok, good bye!"));
      reqF.onComplete(_ => Main.stopped.set(true));
    }
  }
}
object KillCmd {
  def props(requests: RequestHelper): Props = Props(new KillCmd(requests))
}
