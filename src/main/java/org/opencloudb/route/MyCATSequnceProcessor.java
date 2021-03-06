package org.opencloudb.route;

import io.netty.buffer.ByteBuf;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.log4j.Logger;
import org.opencloudb.MycatSystem;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.net.FrontSession;
import org.opencloudb.net.mysql.EOFPacket;
import org.opencloudb.net.mysql.FieldPacket;
import org.opencloudb.net.mysql.ResultSetHeaderPacket;
import org.opencloudb.net.mysql.RowDataPacket;
import org.opencloudb.parser.ExtNodeToString4SEQ;
import org.opencloudb.parser.SQLParserDelegate;
import org.opencloudb.util.StringUtil;

import com.foundationdb.sql.parser.QueryTreeNode;
import com.foundationdb.sql.unparser.NodeToString;

public class MyCATSequnceProcessor {
	private static final Logger LOGGER = Logger
			.getLogger(MyCATSequnceProcessor.class);
	private ConcurrentLinkedQueue<SessionSQLPair> seqSQLQueue = new ConcurrentLinkedQueue<SessionSQLPair>();

	public MyCATSequnceProcessor() {
		new ExecuteThread().start();
	}

	public void addNewSql(SessionSQLPair pair) {
		seqSQLQueue.offer(pair);
	}

	private void outRawData(FrontSession session,String value) {
		byte packetId = 0;
		int fieldCount = 1;
		String charset= session.getConInfo().getCharset();
		ByteBuf byteBuf = session.allocate(128,1024);
		ResultSetHeaderPacket headerPkg = new ResultSetHeaderPacket();
		headerPkg.fieldCount = fieldCount;
		headerPkg.packetId = ++packetId;

		byteBuf = headerPkg.write(byteBuf);
		FieldPacket fieldPkg = new FieldPacket();
		fieldPkg.packetId = ++packetId;
		fieldPkg.name = StringUtil.encode("SEQUNCE", charset);
		byteBuf = fieldPkg.write(byteBuf);
		EOFPacket eofPckg = new EOFPacket();
		eofPckg.packetId = ++packetId;
		byteBuf = eofPckg.write(byteBuf);

		RowDataPacket rowDataPkg = new RowDataPacket(fieldCount);
		rowDataPkg.packetId = ++packetId;
		rowDataPkg.add(StringUtil.encode(value, charset));
		byteBuf = rowDataPkg.write(byteBuf);
		// write last eof
		EOFPacket lastEof = new EOFPacket();
		lastEof.packetId = ++packetId;
		byteBuf = lastEof.write(byteBuf);

		// write buffer
		session.write(byteBuf);
	}

	private void executeSeq(SessionSQLPair pair) {
		try {

			// @micmiu 扩展NodeToString实现自定义全局序列号
			NodeToString strHandler = new ExtNodeToString4SEQ(MycatSystem
					.getInstance().getConfig().getSystem()
					.getSequnceHandlerType());
			// 如果存在sequence 转化sequence为实际数值
			String charset = pair.session.getConInfo().getCharset();
			QueryTreeNode ast = SQLParserDelegate.parse(pair.sql,
					charset == null ? "utf-8" : charset);
			String sql = strHandler.toString(ast);
			if (sql.toUpperCase().startsWith("SELECT")) {
				String value=sql.substring("SELECT".length()).trim();
				outRawData(pair.session,value);
				return;
			}
			pair.session.routeEndExecuteSQL(sql, pair.type,
					pair.schema);

		} catch (Exception e) {
			LOGGER.error(e);
			
			pair.session.writeErrMessage(ErrorCode.ER_YES,
					"mycat sequnce err." + e);
			return;
		}
	}

	class ExecuteThread extends Thread {
		public void run() {
			while (true) {
				SessionSQLPair pair = null;
				try {
					pair = seqSQLQueue.poll();
					if (pair == null) {
						Thread.sleep(100);
					} else {
						executeSeq(pair);
					}
				} catch (Exception e) {
					LOGGER.error(e);
				}
			}
		}
	}
}
