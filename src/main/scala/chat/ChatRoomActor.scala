/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package chat

import akka.actor._

import com.redis.{RedisClient, PubSubMessage, S, U, E, M}
import scala.concurrent.ExecutionContext
import com.redis.RedisClient
import com.typesafe.config.ConfigFactory

object ChatRoomActor {
  case object Join
  case class ChatMessage(message: String)
}

/**
  * The actor that is created for each chat room. Various kinds of messages
  * that represent chat room-related events such as comment sending or delivery
  * termination are all routed to this actor.
  *
  * ChatRoomActor is in charge of
  * publishing comments to Redis or storing comments in Redis for comment
  * synchronization between servers (more details on the following section).
  *
  * It also passes to UserActor all messages destined to the clients.
  */
class ChatRoomActor extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val system = ActorSystem("heimdallr", ConfigFactory.load())

  import ChatRoomActor._
  // TODO: We may want to manage user name.
  // Then, Map data structure can be used, instead of Set.
  var users: Set[ActorRef] = Set.empty

  val chatRoomName = self.path.name
  var redisIp = system.settings.config.getString("akka.redis-ip")
  var redisPort = system.settings.config.getInt("akka.redis-port")
  val s = new RedisClient(redisIp, redisPort)
  val p = new RedisClient(redisIp, redisPort)

  s.subscribe(chatRoomName) { pubsub =>
    pubsub match {
      case S(channel, no) => log.info("subscribed to " + channel + " and count = " + no)
      case U(channel, no) => log.info("unsubscribed from " + channel + " and count = " + no)
      case E(exception) => log.info(exception + "Fatal error caused consumer dead. " +
        "Need to reconnecting to master or connect to backup")

      case M(channel, msg) => MessageHandler.processing(users, msg)
    }
  }

  /**
    * Receives messages from Server, and synchronizes messages with others.
    * @return nothing
    */
  def receive = {
    case Join =>
      users += sender()
      // we also would like to remove the user when its actor is stopped
      context.watch(sender())

    case Terminated(user) =>
      users -= user

    case msg: ChatMessage =>
      // publish message to all chatRoomActor that subscribes same chatRoomName
      log.info("publish message to chanel: " + chatRoomName)
      p.publish(chatRoomName, msg.message);
  }
}
