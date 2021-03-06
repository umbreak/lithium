package com.swissborg.lithium

import akka.cluster.{Member, MemberStatus, UniqueAddress}
import cats.Eq
import com.swissborg.lithium.implicits._
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final private[lithium] case class SimpleMember(uniqueAddress: UniqueAddress, status: MemberStatus)

private[lithium] object SimpleMember {
  def fromMember(member: Member): SimpleMember = SimpleMember(member.uniqueAddress, member.status)

  implicit val simpleMemberEncoder: Encoder[SimpleMember] = deriveEncoder
  implicit val simpleMemberEq: Eq[SimpleMember]           = Eq.fromUniversalEquals
}
