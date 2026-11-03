package com.gimreminder;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class StorageSessionTest
{
    private static Map<Integer,Integer> items(int... pairs)
    {
        Map<Integer,Integer> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i+1]);
        return map;
    }

    @Test public void withdrawRequiresSave()
    {
        StorageSession session = new StorageSession();
        session.observe(items(1, 3));
        session.observe(items(1, 2));
        assertTrue(session.close().isEmpty());
    }

    @Test public void savedWithdrawalAndReturnAreNetChanges()
    {
        StorageSession session = new StorageSession();
        session.observe(items(1, 3));
        session.observe(items(1, 1));
        session.observe(items(1, 2));
        session.saveRequested();
        assertEquals(Integer.valueOf(-1), session.close().get(1));
    }

    @Test public void savedDepositAndNewItem()
    {
        StorageSession session = new StorageSession();
        session.observe(items(1, 1));
        session.observe(items(1, 2, 3, 1));
        session.saveRequested();
        assertEquals(items(1, 1, 3, 1), session.close());
    }

    @Test public void savedWithdrawIsBorrowOnServer()
    {
        StorageSession session = new StorageSession();
        session.observe(items()); // Empty player inventory when shared storage opens.
        session.observe(items(11980, 1)); // Player withdraws the Brimstone ring.
        session.saveRequested();
        assertEquals(items(11980, -1), session.closeAsStorageMovements());
        assertTrue(session.closeAsStorageMovements().isEmpty()); // No duplicate event.
    }

    @Test public void savedDepositIsReturnOnServer()
    {
        StorageSession session = new StorageSession();
        session.observe(items(11980, 1));
        session.observe(items());
        session.saveRequested();
        assertEquals(items(11980, 1), session.closeAsStorageMovements());
    }

    @Test public void xCloseWithCommittedWithdrawalIsBorrowedOnce()
    {
        StorageSession session = new StorageSession();
        session.observe(items());
        session.observe(items(11980, 1)); // Temporary inventory before pressing X
        assertTrue(session.differsFromOpeningInventory(items(11980, 1)));
        assertEquals(items(11980, -1), session.closeFromConfirmedInventory(items(11980, 1)));
        assertTrue(session.closeFromConfirmedInventory(items(11980, 1)).isEmpty());
    }

    @Test public void xCloseWithCommittedReturnIsReturned()
    {
        StorageSession session = new StorageSession();
        session.observe(items(11980, 1));
        session.observe(items());
        assertEquals(items(11980, 1), session.closeFromConfirmedInventory(items()));
    }

    @Test public void xCloseWithoutCommitIsIgnored()
    {
        StorageSession session = new StorageSession();
        session.observe(items());
        session.observe(items(11980, 1)); // Changes to temporary inventory only
        assertFalse(session.differsFromOpeningInventory(items()));
        assertTrue(session.closeFromConfirmedInventory(items()).isEmpty());
        assertTrue(session.isOpen()); // May still receive a real save on a later tick
        session.reset();
    }

    @Test public void unsavedWithdrawalIsNotBorrowed()
    {
        StorageSession session = new StorageSession();
        session.observe(items());
        session.observe(items(11980, 1));
        assertTrue(session.closeAsStorageMovements().isEmpty());
    }

    @Test public void closeResetsSession()
    {
        StorageSession session = new StorageSession();
        session.observe(items(1, 1));
        session.observe(items());
        session.saveRequested();
        assertEquals(Integer.valueOf(-1), session.close().get(1));
        session.observe(items(1, 1));
        assertTrue(session.close().isEmpty());
    }
}
