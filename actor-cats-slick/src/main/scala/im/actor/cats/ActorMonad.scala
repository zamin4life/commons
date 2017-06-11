package im.actor.cats

import cats.Monad

/**
  * Created by diego on 11/06/17.
  */
trait ActorMonad {

  protected def defaultTailRecM[F[_], A, B](a: A)(f: A => F[Either[A,B]])(implicit F: Monad[F]): F[B] =
    F.flatMap(f(a)) {
      case Left(a2) => defaultTailRecM(a2)(f)
      case Right(b) => F.pure(b)
    }

}
