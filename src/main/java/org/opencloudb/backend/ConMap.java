package org.opencloudb.backend;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.opencloudb.MycatSystem;

public class ConMap {
	// key -schema
	private final ConcurrentHashMap<String, ConQueue> items = new ConcurrentHashMap<String, ConQueue>();

	public ConQueue getSchemaConQueue(String schema) {
		ConQueue queue = items.get(schema);
		if (queue == null) {
			ConQueue newQueue = new ConQueue();
			queue = items.putIfAbsent(schema, newQueue);
			return (queue == null) ? newQueue : queue;
		}
		return queue;
	}

	public BackendConnection tryTakeCon(final ConnectionMeta conMeta) {
		final ConQueue queue = items.get(conMeta.getSchema());
		BackendConnection con = tryTakeCon(queue, conMeta);
		if (con != null) {
			return con;
		} else {
			for (ConQueue queue2 : items.values()) {
				if (queue != queue2) {
					con = tryTakeCon(queue2, conMeta);
					if (con != null) {
						return con;
					}
				}
			}
		}
		return null;

	}

	private BackendConnection tryTakeCon(ConQueue queue,
			final ConnectionMeta conMeta) {

		BackendConnection con = null;
		if (queue != null && ((con = queue.takeIdleCon(conMeta)) != null)) {
			return con;
		} else {
			return null;
		}

	}

	public Collection<ConQueue> getAllConQueue() {
		return items.values();
	}

	public int getActiveCountForSchema(String schema,
			PhysicalDatasource dataSouce) {
		int total = 0;
		ConcurrentMap<Long, BackendConnection> map = MycatSystem.getInstance()
				.getBackends();
		for (BackendConnection con : map.values()) {
			if (con.getSchema().equals(schema) && dataSouce.isMyConnection(con)) {
				if (con.isBorrowed()) {
					total++;
				}
			}
		}
		return total;
	}

	public int getActiveCountForDs(PhysicalDatasource dataSouce) {
		int total = 0;
		ConcurrentMap<Long, BackendConnection> map = MycatSystem.getInstance()
				.getBackends();
		for (BackendConnection con : map.values()) {
			if (dataSouce.isMyConnection(con)) {
				if (con.isBorrowed() && !con.isClosed()) {
					total++;
				}
			}
		}

		return total;
	}

	public void clearConnections(String reason, PhysicalDatasource dataSouce) {

		ConcurrentMap<Long, BackendConnection> map = MycatSystem.getInstance()
				.getBackends();
		Iterator<Entry<Long, BackendConnection>> itor = map.entrySet()
				.iterator();
		while (itor.hasNext()) {
			Entry<Long, BackendConnection> entry = itor.next();
			BackendConnection con = entry.getValue();
			if (dataSouce.isMyConnection(con)) {
				con.close(reason);
				itor.remove();
			}

		}
		items.clear();
	}

}
