package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.type.InstanceType;

public record MemberConstant(InstanceType owner, MemberIdentifier member) implements Constant {
}
