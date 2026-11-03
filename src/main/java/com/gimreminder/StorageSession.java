package com.gimreminder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A storage session is provisional until the game signals that changes were saved.
 * A preview, search refresh, or closing without saving must not produce Discord posts.
 */
final class StorageSession
{
    private Map<Integer, Integer> before;
    private Map<Integer, Integer> current;
    private boolean saved;

    boolean isOpen()
    {
        return before != null;
    }

    void observe(Map<Integer, Integer> amounts)
    {
        Map<Integer, Integer> safeCopy = new HashMap<>(amounts);
        if (before == null)
        {
            before = safeCopy;
        }
        current = safeCopy;
    }

    boolean isSaved() { return saved; }

    /**
     * Closing the Group Storage X can commit the transaction without the
     * LoadingIconModal Saving callback. In that case, the *real* player
     * inventory (INV, never INV_PLAYER_TEMP) is the authoritative result.
     * A cancelled close leaves INV unchanged, so it must not emit a borrow.
     */
    boolean differsFromOpeningInventory(Map<Integer, Integer> authoritativeInventory)
    {
        return isOpen() && authoritativeInventory != null && !before.equals(authoritativeInventory);
    }

    Map<Integer, Integer> closeFromConfirmedInventory(Map<Integer, Integer> authoritativeInventory)
    {
        if (!differsFromOpeningInventory(authoritativeInventory)) return Collections.emptyMap();
        current = new HashMap<>(authoritativeInventory);
        saved = true;
        return closeAsStorageMovements();
    }

    void saveRequested()
    {
        if (isOpen()) saved = true;
    }

    Map<Integer, Integer> close()
    {
        if (!isOpen()) return Collections.emptyMap();
        Map<Integer, Integer> changes = new HashMap<>();
        if (saved && current != null)
        {
            for (Integer id : before.keySet())
            {
                int delta = current.getOrDefault(id, 0) - before.getOrDefault(id, 0);
                if (delta != 0) changes.put(id, delta);
            }
            for (Integer id : current.keySet())
            {
                if (!before.containsKey(id))
                {
                    int delta = current.get(id);
                    if (delta != 0) changes.put(id, delta);
                }
            }
        }
        reset();
        return changes;
    }

    /**
     * The saved player-inventory delta has opposite sign to the shared bank:
     * inventory +1 means borrowed from storage (-1), inventory -1 means
     * returned to storage (+1).
     */
    Map<Integer, Integer> closeAsStorageMovements()
    {
        Map<Integer, Integer> result = close();
        result.replaceAll((id, delta) -> -delta);
        return result;
    }

    void reset()
    {
        before = null;
        current = null;
        saved = false;
    }
}
