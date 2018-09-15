package com.lkroll.sayasbot

import cats.{ Monad, Id }
import cats.data.OptionT
import net.katsstuff.ackcord.commands._
import net.katsstuff.ackcord.data._
import net.katsstuff.ackcord.CacheSnapshot

/**
 * A command that can only be used by a single user.
 */
case class ByUser(userId: UserId) extends CmdFilter {
  override def isAllowed[F[_]](userId: UserId, guildId: GuildId)(implicit c: CacheSnapshot[F], F: Monad[F]): F[Boolean] =
    Monad[F].pure(this.userId == userId);

  override def isAllowed[F[_]](msg: Message)(implicit c: CacheSnapshot[F], F: Monad[F]): F[Boolean] =
    msg.authorUserId
      .map(_ == userId)
      .map(Monad[F].pure(_))
      .getOrElse(Monad[F].pure(false))

  override def errorMessage[F[_]](msg: Message)(implicit c: CacheSnapshot[F], F: Monad[F]): OptionT[F, String] =
    msg.authorUserId.map(_.resolve)
      .map(_.map(author =>
        s"Nice try, but you don't have the rights for this ${author.username}"))
      .getOrElse(OptionT.pure[F](s"You aren't resolvable, so you probably don't have any rights."))

}
