package me.darknet.dex.tree.definitions.constant;

import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.type.InstanceType;

public record EnumConstant(InstanceType owner, MemberIdentifier field) implements Constant {
}
