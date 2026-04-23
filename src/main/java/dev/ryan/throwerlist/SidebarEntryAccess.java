package dev.ryan.throwerlist;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public record SidebarEntryAccess(
    Method nameMethod,
    Method scoreMethod,
    Method scoreWidthMethod,
    Constructor<?> constructor
) {
}
