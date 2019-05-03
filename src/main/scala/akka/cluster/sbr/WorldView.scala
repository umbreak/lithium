package akka.cluster.sbr

import akka.actor.Address
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus.{Joining, Removed, WeaklyUp}
import akka.cluster.sbr.SBRFailureDetector.{IndirectlyConnected, Reachable, SBRReachability, Unreachable}
import akka.cluster.sbr.WorldView.Status
import akka.cluster.{Member, MemberStatus, UniqueAddress}

import scala.collection.immutable.SortedSet

/**
 * Represents the view of the cluster from the point of view of the
 * `selfNode`.
 */
final case class WorldView private[sbr] (
  private[sbr] val selfUniqueAddress: UniqueAddress,
  private[sbr] val selfStatus: Status,
  /**
   * The ordering on nodes is defined on their unique address,
   * ignoring for instance the status.
   * As a result, it cannot contain duplicate nodes.
   *
   * Care needs need to be taken when replacing a node with one where
   * the status changed in the set. First it has it to be removed and
   * then added. Only adding it will not override the value as they
   * are equal given the ordering.
   */
  private[sbr] val otherMembersStatus: Map[UniqueAddress, Status],
  /**
   * Removed members are kept as the information
   * is useful to detect the case when the removal
   * might not have been seen by a partition.
   */
  private[sbr] val removedMembersSeenBy: Map[UniqueAddress, Set[Address]] // todo when to cleanup?
) {
  assert(!otherMembersStatus.contains(selfUniqueAddress), s"$otherMembersStatus <- $selfUniqueAddress")

  lazy val selfNode: Node = toNode(selfStatus.member, selfStatus.reachability)

  /**
   * All the nodes in the cluster.
   */
  lazy val nodes: SortedSet[Node] = {
    val otherNodes: Seq[Node] = otherMembersStatus.values.map {
      case Status(member, reachability, _) => toNode(member, reachability)
    }(collection.breakOut)

    SortedSet(selfNode +: otherNodes: _*)
  }

  lazy val members: SortedSet[Member] = nodes.map(_.member)

  /**
   * The nodes that need to be considered in split-brain resolutions.
   *
   * A node is to be considered when it isn't in the "Joining" or "WeaklyUp"
   * states. These status are ignored since a node can join and become
   * weakly-up during a network-partition.
   */
  lazy val consideredNodes: Set[Node] = nodes.collect { case node if shouldBeConsidered(node) => node }

  /**
   * The nodes with the given role, that need to be considered in
   * split-brain resolutions.
   */
  def consideredNodesWithRole(role: String): Set[Node] =
    if (role.nonEmpty) consideredNodes.filter(_.member.roles.contains(role)) else consideredNodes

  /**
   * The reachable nodes that need to be considered in split-brain resolutions.
   */
  lazy val consideredReachableNodes: Set[ReachableNode] = reachableNodes.collect {
    case n if shouldBeConsidered(n) => n
  }

  /**
   * The reachable nodes with the given role, that need to be
   * considered in split-brain resolutions.
   */
  def consideredReachableNodesWithRole(role: String): Set[ReachableNode] =
    if (role.nonEmpty) consideredReachableNodes.filter(_.member.roles.contains(role)) else consideredReachableNodes

  /**
   * All the reachable nodes.
   */
  lazy val reachableNodes: Set[ReachableNode] = nodes.collect { case r: ReachableNode => r }

  /**
   * All the unreachable nodes.
   */
  lazy val unreachableNodes: Set[UnreachableNode] = nodes.collect { case r: UnreachableNode => r }

  /**
   * All the indirectly connected nodes.
   */
  lazy val indirectlyConnectedNodes: Set[IndirectlyConnectedNode] = nodes.collect {
    case r: IndirectlyConnectedNode => r
  }

  lazy val removedMembers: Set[UniqueAddress] = removedMembersSeenBy.keySet

  /**
   * The unreachable nodes that need to be considered in split-brain resolutions.
   */
  lazy val consideredUnreachableNodes: Set[UnreachableNode] = unreachableNodes.collect {
    case n if shouldBeConsidered(n) => n
  }

  /**
   * The unreachable nodes with the given role, that need to be
   * considered in split-brain resolutions.
   */
  def consideredUnreachableNodesWithRole(role: String): Set[UnreachableNode] =
    if (role.nonEmpty) consideredUnreachableNodes.filter(_.member.roles.contains(role)) else consideredUnreachableNodes

  def updateMember(member: Member, seenBy: Set[Address]): WorldView =
    if (member.uniqueAddress == selfUniqueAddress) {
      copy(selfUniqueAddress = member.uniqueAddress, selfStatus = selfStatus.withMember(member).withSeenBy(seenBy))
    } else {
      otherMembersStatus
        .get(member.uniqueAddress)
        .fold(
          // Assumes the member is reachable if seen for the 1st time.
          copy(otherMembersStatus = otherMembersStatus + (member.uniqueAddress -> Status(member, Reachable, seenBy)))
        )(
          s =>
            copy(
              otherMembersStatus = otherMembersStatus - member.uniqueAddress + (member.uniqueAddress -> s
                .withMember(member)
                .withSeenBy(seenBy))
          )
        )
    }

  def memberRemoved(member: Member, seenBy: Set[Address]): WorldView =
    if (member.uniqueAddress == selfUniqueAddress) {
      copy(selfUniqueAddress = member.uniqueAddress, selfStatus = selfStatus.withMember(member).withSeenBy(seenBy)) // ignore only update // todo is it safe?
    } else {
      otherMembersStatus
        .get(member.uniqueAddress)
        .fold(copy(removedMembersSeenBy = removedMembersSeenBy + (member.uniqueAddress -> seenBy))) { _ =>
          copy(otherMembersStatus = otherMembersStatus - member.uniqueAddress,
               removedMembersSeenBy = removedMembersSeenBy + (member.uniqueAddress -> seenBy))
        }
    }

  /**
   * Change the `node`'s state to `Reachable`.
   */
  def reachableMember(member: Member): WorldView = updateReachability(member, Reachable)

  /**
   * Change the `node`'s status to `Unreachable`.
   */
  def unreachableMember(member: Member): WorldView = updateReachability(member, Unreachable)

  /**
   * Change the `node`'s status to `IndirectlyConnected`.
   */
  def indirectlyConnectedMember(member: Member): WorldView = updateReachability(member, IndirectlyConnected)

  def allSeenBy(seenBy: Set[Address]): WorldView =
    copy(selfStatus = selfStatus.withSeenBy(seenBy),
         otherMembersStatus = otherMembersStatus.mapValues(s => s.withSeenBy(seenBy)))

  def seenBy(member: Member): Set[Address] =
    if (member.uniqueAddress == selfUniqueAddress) selfStatus.seenBy
    else
      otherMembersStatus
        .get(member.uniqueAddress)
        .fold(removedMembersSeenBy.getOrElse(member.uniqueAddress, Set.empty))(_.seenBy)

  def changeSelf(member: Member): WorldView =
    if (member.uniqueAddress == selfUniqueAddress) this
    else {
      val newSelfStatus = otherMembersStatus.getOrElse(member.uniqueAddress, Status(member, Reachable, Set.empty))

      selfStatus.member.status match {
        case Removed =>
          copy(
            selfUniqueAddress = member.uniqueAddress,
            selfStatus = newSelfStatus,
            otherMembersStatus = otherMembersStatus - member.uniqueAddress,
            removedMembersSeenBy = removedMembersSeenBy - member.uniqueAddress + (selfUniqueAddress -> selfStatus.seenBy)
          )

        case _ =>
          copy(
            selfUniqueAddress = member.uniqueAddress,
            selfStatus = newSelfStatus,
            otherMembersStatus = otherMembersStatus - member.uniqueAddress + (selfUniqueAddress -> selfStatus),
            removedMembersSeenBy = removedMembersSeenBy - member.uniqueAddress
          )
      }
    }

  private def updateReachability(member: Member, reachability: SBRReachability): WorldView =
    if (member.uniqueAddress == selfUniqueAddress) {
      copy(selfUniqueAddress = member.uniqueAddress, selfStatus = selfStatus.withReachability(reachability))
    } else {
      otherMembersStatus
        .get(member.uniqueAddress)
        .fold(
          copy(
            otherMembersStatus = otherMembersStatus + (member.uniqueAddress -> Status(member, reachability, Set.empty))
          )
        ) { s =>
          copy(
            otherMembersStatus = otherMembersStatus - member.uniqueAddress + (member.uniqueAddress -> s
              .withReachability(reachability)) // todo update member?
          )
        }
    }

  private def shouldBeConsidered(node: Node): Boolean = node match {
    case UnreachableNode(member) => member.status != Joining && member.status != WeaklyUp
    case ReachableNode(member)   => member.status != Joining && member.status != WeaklyUp

    // When indirectly connected nodes are tracked they do not
    // appear in the considered nodes as they will be downed
    // in parallel.
    case _: IndirectlyConnectedNode => false
  }

  private def toNode(member: Member, reachability: SBRReachability): Node =
    reachability match {
      case Reachable           => ReachableNode(member)
      case Unreachable         => UnreachableNode(member)
      case IndirectlyConnected => IndirectlyConnectedNode(member)
    }
}

object WorldView {
  def init(selfMember: Member): WorldView =
    new WorldView(selfMember.uniqueAddress,
                  Status(selfMember, Reachable, Set(selfMember.address)),
                  Map.empty,
                  Map.empty)

  def fromSnapshot(selfMember: Member, state: CurrentClusterState): WorldView = {
    val w = WorldView.init(selfMember)

    val w1 = (state.members -- state.unreachable).foldLeft(w) {
      case (w, member) =>
        member.status match {
          case Removed => w.reachableMember(member).memberRemoved(member, Set.empty)
          case _       => w.reachableMember(member)
        }
    }

    state.unreachable
      .foldLeft(w1) {
        case (w, member) =>
          member.status match {
            case Removed => w.unreachableMember(member).memberRemoved(member, Set.empty)
            case _       => w.unreachableMember(member)
          }
      }
      .allSeenBy(state.seenBy)
  }

  def fromNodes(selfNode: Node, seenBy: Set[Address], otherNodesSeenBy: Map[Node, Set[Address]]): WorldView = {
    assert(!otherNodesSeenBy.contains(selfNode))

    val (selfUniqueAddress, selfStatus) = convert(selfNode, seenBy)

    val (removed, others) = otherNodesSeenBy.partition(_._1.member.status == Removed)

    WorldView(
      selfUniqueAddress,
      selfStatus,
      others.map((convert _).tupled),
      removed.map((convert _).tupled).mapValues(_.seenBy)
    )
  }

  private def convert(node: Node, seenBy: Set[Address]): (UniqueAddress, Status) =
    node.member.uniqueAddress -> (node match {
      case _: UnreachableNode         => Status(node.member, Unreachable, seenBy)
      case _: ReachableNode           => Status(node.member, Reachable, seenBy)
      case _: IndirectlyConnectedNode => Status(node.member, IndirectlyConnected, seenBy)
    })

  final case class Status(member: Member, reachability: SBRReachability, seenBy: Set[Address]) {
    def withSeenBy(seenBy: Set[Address]): Status                = copy(seenBy = seenBy)
    def withReachability(reachability: SBRReachability): Status = copy(reachability = reachability)
    def withMember(member: Member): Status                      = copy(member = member)
  }
}
