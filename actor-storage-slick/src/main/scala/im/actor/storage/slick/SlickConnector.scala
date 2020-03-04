package im.actor.storage.slick

import im.actor.storage.Connector
import im.actor.storage.api._
import im.actor.storage.slick.MyPostgresProfile.api._
import org.slf4j.LoggerFactory
import slick.jdbc.GetResult

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class SlickConnector(db: Database)(implicit ec: ExecutionContext) extends Connector {

  private val tables = mutable.Set.empty[String]

  override def runSync[R](action: Action[R])(implicit timeout: FiniteDuration): R =
    Await.result(run(action), timeout)

  override def run[R](action: Action[R]): Future[R] = {
    createTableIfNotExists(action.name)
    for {
      result <- (action match {
        case GetAction(name, key) => get(name, key)
        case GetByPrefixAction(name, key) => getByPrefix(name, key)
        case UpsertAction(name, key, value) => upsert(name, key, value)
        case DeleteAction(name, key) => delete(name, key)
        case GetKeysAction(name) => getKeys(name)
      }).asInstanceOf[Future[R]]
    } yield result
  }

  private def createTableIfNotExists(name: String): Unit = synchronized {
    if (!tables.contains(name)) {
      val tName = tableName(name)
      Await.result(
        db.run(sqlu"""CREATE TABLE IF NOT EXISTS #$tName (key TEXT, value BYTEA, PRIMARY KEY (key))""") map (_ => ()),
        10 seconds)
      tables += name
    }
  }

  private def tableName(name: String) = s"kv_$name"

  implicit val getByteArray = GetResult(r ⇒ r.nextBytes())

  private def get(name: String, key: String): Future[Option[Array[Byte]]] =
    db.run(sql"""SELECT value FROM #${tableName(name)} WHERE key = $key""".as[Array[Byte]].headOption)

  private def getByPrefix(name: String, keyPrefix: String): Future[Vector[(String, Array[Byte])]] =
    db.run(sql"""SELECT key, value FROM #${tableName(name)} WHERE key like '#$keyPrefix%'""".as[(String, Array[Byte])])

  private def upsert(name: String, key: String, value: Array[Byte]): Future[Int] = {
    val tName = tableName(name)
    val action: DBIO[Int] = for {
      count <- sql"SELECT COUNT(*) FROM #$tName WHERE KEY = $key".as[Int]
      exists = count.headOption.exists(_ > 0)
      result <- if (exists)
        sqlu"UPDATE #$tName SET value = $value WHERE key = $key"
      else
        sqlu"INSERT INTO #$tName VALUES ($key, $value)"

    } yield result
    db.run(action.transactionally)
  }

  private def delete(name: String, key: String) =
    db.run(sqlu"""DELETE FROM #${tableName(name)} WHERE key = $key""")

  private def getKeys(name: String) =
    db.run(sql"""SELECT key FROM #${tableName(name)}""".as[String])
}
