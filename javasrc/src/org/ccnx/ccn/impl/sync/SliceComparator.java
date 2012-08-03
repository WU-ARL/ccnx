/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.ccnx.ccn.impl.sync;

import java.io.IOException;
import java.util.Queue;
import java.util.Stack;
import java.util.Timer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNSyncHandler;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ConfigSlice;
import org.ccnx.ccn.io.content.SyncNodeComposite;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeElement;
import org.ccnx.ccn.io.content.SyncNodeComposite.SyncNodeType;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;

/**
 * Note: We purposely don't decode SyncNodeComposites in handlers since they are big and slow and we risk
 * timing out the handler by doing so. 
 *
 */
public class SliceComparator implements Runnable {
	public static final int DECODER_SIZE = 756;
	public static enum SyncCompareState {INIT, PRELOAD, BUSY, COMPARE, DONE};

	public final int COMPARE_INTERVAL = 100; // ms
	protected BinaryXMLDecoder _decoder;
	protected Object _timerLock = new Object();
	protected boolean _needToCompare = true;
	protected boolean _keepComparing = false;
	protected Timer _timer = new Timer(false);
	protected NodeFetchHandler _nfh = new NodeFetchHandler();
	protected Object _compareLock = new Object();
	
	protected ConfigSlice _slice;
	protected CCNSyncHandler _callback;
	protected CCNHandle _handle;
	protected ProtocolBasedSyncMonitor _pbsm = null;

	protected Stack<SyncTreeEntry> _current = new Stack<SyncTreeEntry>();
	protected Stack<SyncTreeEntry> _next = new Stack<SyncTreeEntry>();
	protected SyncCompareState _state = SyncCompareState.DONE;
	protected Queue<byte[]> _pendingEntries = new ConcurrentLinkedQueue<byte[]>();
	protected SyncTreeEntry _currentRoot = null;
	
	public SliceComparator(ProtocolBasedSyncMonitor pbsm, CCNSyncHandler callback, ConfigSlice slice, CCNHandle handle) {
		_pbsm = pbsm;
		_slice = slice;
		_callback = callback;
		_handle = handle;
		_decoder = new BinaryXMLDecoder();
		_decoder.setInitialBufferSize(DECODER_SIZE);
	}
	
	/**
	 * This will normally be called by the handler in ProtocolBasedSyncMonitor so
	 * it is therefore handler code. 
	 * @param data
	 */
	public void addPending(byte[] data) {
Log.info("Called pending add");
		synchronized (this) {
			_pendingEntries.add(data);
			checkNextRound();
		}
		kickCompare();
	}
	
	/**
	 * Call synchronized with this
	 */
	public void checkNextRound() {
		if (_state == SyncCompareState.DONE) {
			_state = SyncCompareState.INIT;
		}	
	}
	
	public CCNSyncHandler getCallback() {
		return _callback;
	}

	private void kickCompare() {
		synchronized (_timerLock) {
			_keepComparing = true;
			if (_needToCompare) {
				SystemConfiguration._systemTimers.schedule(this, COMPARE_INTERVAL, TimeUnit.MILLISECONDS);
				_needToCompare = false;
			}
		}
	}
	
	/**
	 * 
	 * @param sr
	 * @param topo
	 * @param srt
	 * @return false if no preloads requested
	 */
	private boolean doPreload(SyncTreeEntry srt) {
		Boolean ret = false;
		synchronized (this) {
			SyncNodeComposite snc = srt.getNodeX(_decoder);
			if (null != snc) {
				for (SyncNodeElement sne : snc.getRefs()) {
					if (sne.getType() == SyncNodeType.HASH) {
						byte[] hash = sne.getData();
						ret = requestNode(hash);
					}
				}
			}
		}
		return ret;
	}
	
	/**
	 * 
	 * @param topo
	 * @param sr
	 * @param hash
	 * @return true if request made
	 */
	private boolean requestNode(byte[] hash) {
		boolean ret = false;
		synchronized (this) {
			SyncTreeEntry tsrt = _pbsm.addHash(hash, null);
			if (tsrt.getNodeX(_decoder) == null && !tsrt.getPending()) {
				tsrt.setPending(true);
				Log.info("requesting node for hash: {0}", Component.printURI(hash));
				ret = getHash(hash, false);
			}
		}
		return ret;
	}
	
	private boolean getHash(byte[] hash, boolean next) {
		boolean ret = false;
		Interest interest = new Interest(new ContentName(_slice.topo, Sync.SYNC_NODE_FETCH_MARKER, _slice.getHash(), hash));
		interest.scope(1);
		if (next) {
Log.info(Log.FAC_TEST, "Requesting next node");
			Exclude exclude = Exclude.uptoFactory(hash);
			interest.exclude(exclude);
		}		
		if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
			Log.info(Log.FAC_TEST, "Requesting node for hash: {0}, next = {1}", interest.name(), next);
		try {
			_handle.expressInterest(interest, _nfh);
			ret = true;
		} catch (IOException e) {
			Log.warning(Log.FAC_SYNC, "Preload failed: {0}", e.getMessage());
		}
		return ret;
	}
	
	private void doComparison() throws SyncException {
Log.info("started compare for slice {0}", Component.printURI(_slice.getHash()));

		SyncTreeEntry srtY = null;
		SyncTreeEntry srtX = null;
		
		synchronized (this) {
			srtY = getHead(_next);
			srtX = getHead(_current);
		}
		while (null != srtY) {
			boolean lastPos = false;
			SyncNodeComposite sncX = null;
			SyncNodeComposite sncY = null;
			SyncNodeComposite.SyncNodeElement sneX = null;
			SyncNodeComposite.SyncNodeElement sneY = null;
			synchronized (this) {
				while (null != srtY && srtY.lastPos()) {
					SyncTreeEntry tste = getHead(_next);
					if (tste == srtY)
						tste = pop(_next);
					srtY = tste;
				}
				if (null == srtY) {
					break;
				}
					
				while (null != srtX && srtX.lastPos()) {
					SyncTreeEntry tste = getHead(_current);
					if (tste == srtX)
						tste = pop(_current);
					srtX = tste;
				}
				sncY = srtY.getNodeX(_decoder);
				if (null != sncY) {
					sneY = srtY.getCurrentElement();
					if (null == sneY)
						lastPos = true;
				}
				if (null != srtX) {
					sncX = srtX.getNodeX(_decoder);
					if (null != sncX) {
						sneX = srtX.getCurrentElement();
					}
				}
			}
			if (null == sneX && null != sneY && !lastPos) {
Log.info("Comparing hash for {0}, type is {1}", Component.printURI(srtY.getHash()), sneY.getType());
				switch (sneY.getType()) {
				case LEAF:
					doCallback(sneY);
					synchronized (this) {
						srtY.incPos();
					}
					break;
				case HASH:
					SyncTreeEntry entry = handleHashNode(sneY);
					if (null != entry) {
Log.info("Got entry for {0}", Component.printURI(sneY.getData()));
						entry.setPos(0);
						push(entry, _next);
					} else {
Log.info("Missed entry for {0}", Component.printURI(sneY.getData()));
						return;
					}
					synchronized (this) {
						srtY.incPos();
					}
					break;
				default:
					break;
				}
			} else if (null == sneY  && !lastPos) {
				Log.info("No data for {0}, pos is {1}", Component.printURI(srtY.getHash()), srtY.getPos());
				requestNode(srtY.getHash());
				return;
			} else if (null != sneX) {
				SyncNodeType typeX = sneX.getType();
				SyncNodeType typeY = sneY.getType();
				if (typeX == SyncNodeType.LEAF && typeY == SyncNodeType.LEAF) {
					// Both leaves
					int comp = sneX.getName().compareTo(sneY.getName());
Log.info("name 1 is {0}, name 2 is {1}, comp is {2}", sneX.getName(), sneY.getName(), comp);
					if (comp == 0) {
						srtX.incPos();
						srtY.incPos();
					} else if (comp < 0) {
						srtX.incPos();
					} else {
						doCallback(sneY);
						srtY.incPos();
					}
				} else if (typeX == SyncNodeType.HASH && typeY == SyncNodeType.HASH) {
					// Both nodes
					if (srtX == srtY) {
						srtX.incPos();
						srtY.incPos();
					} else {
						SyncTreeEntry entryY = handleHashNode(sneY);
						if (null != entryY) {
	Log.info("Got entry for {0}", Component.printURI(sneY.getData()));
							entryY.setPos(0);
							SyncTreeEntry entryX = handleHashNode(sneX);
							if (null != entryX) {
								entryX.setPos(0);
								srtX.incPos();
								srtY.incPos();
								push(entryY, _next);
								push(entryX, _current);
							} else
								return;
						} else
							return;
					}
Log.info("Compare LEAF {0} to NODE {1}", Component.printURI(srtX.getHash()), Component.printURI(srtY.getHash()));
				} else if (typeX == SyncNodeType.LEAF && typeY == SyncNodeType.HASH) {
					// X is leaf Y is Node
					if (sneX.getName().compareTo(sncY.getMinName().getName()) < 0)
						srtX.incPos();
					else if (sneX.getName().compareTo(sncY.getMaxName().getName()) == 0) {
						srtX.incPos();
						srtY.incPos();
					} else {
						SyncTreeEntry entry = handleHashNode(sneY);
						if (null != entry) {
	Log.info("Got entry for {0}", Component.printURI(sneY.getData()));
							entry.setPos(0);
							srtY.incPos();
							push(entry, _next);
						} else {
	Log.info("Missed entry for {0}", Component.printURI(sneY.getData()));
							return;
						}
					}
				} else {
					// X is node Y is leaf
					int resMin = sneY.getName().compareTo(sncX.getMinName().getName());
					int resMax = sneY.getName().compareTo(sncX.getMaxName().getName());
					if (resMin < 0) {
						doCallback(sneY);
						srtY.incPos();
					} else if (resMin == 0) {
						srtY.incPos();
					} else if (resMax > 0) {
						srtX.incPos();
					} else if (resMax < 0) {
						SyncTreeEntry entry = handleHashNode(sneX);
						if (null != entry) {
							entry.setPos(0);
							srtX.incPos();
							push(entry, _current);
						} else {
							return;
						}
					} else if (resMax == 0) {
						srtX.incPos();
						srtY.incPos();
					} else {
						throw new SyncException("Bogus comparison");
					}
				}
			}
		}
		synchronized (this) {
			if (_state == SyncCompareState.COMPARE)
				_state = SyncCompareState.DONE;
		}
Log.info("Exited 1 and status was: {0}", _state);
	}
	
	private SyncTreeEntry handleHashNode(SyncNodeElement sne) {
		byte[] hash = sne.getData();
		SyncTreeEntry entry = _pbsm.getHash(hash);
		if (null == entry) {
			if (requestNode(sne.getData())) {
Log.info("requested from compare");
				synchronized (this) {
					_state = SyncCompareState.PRELOAD;
				}
			}
		}
		return entry;
	}
	
	private void doCallback(SyncNodeComposite.SyncNodeElement sne) {
		ContentName name = sne.getName().parent();	// remove digest here
		_callback.handleContentName(_slice, name);
	}
	
	protected void push(SyncTreeEntry srt, Stack<SyncTreeEntry> stack) {
Log.info("pushing entry with hash: {0}", Component.printURI(srt._hash));
		stack.push(srt);
	}
		
	protected SyncTreeEntry pop(Stack<SyncTreeEntry> stack) {
		if (!stack.empty()) {
			SyncTreeEntry srt =  stack.pop();
Log.info("popping entry with hash: {0}", Component.printURI(srt._hash));
		return srt;
		}
	return null;
	}
		
	protected SyncTreeEntry getHead(Stack<SyncTreeEntry> stack) {
		if (! stack.empty()) {
Log.info("Head entry with hash: {0}", Component.printURI(stack.lastElement()._hash));
			return stack.peek();
		}
		return null;
	}
		
	protected void nextRound() {
		synchronized (this) {
			if (_currentRoot != null) {
				_currentRoot.setPos(0);
				push(_currentRoot, _current);
			}
		}
	}
	
	public void run() {
		try {
			do {
Log.info("Run loop for {0} and state is {1}", Component.printURI(_slice.getHash()), _state);
				synchronized (_timerLock) {
					_needToCompare = false;
				}
				switch (_state) {
				case BUSY:
					break;
				case INIT:
					synchronized (this) {
						if (_pendingEntries.size() == 0) {
							_state = SyncCompareState.DONE;
							break;
						}
						nextRound();
						byte[] nextData = _pendingEntries.remove();
						if (null != nextData) {
							SyncNodeComposite snc = new SyncNodeComposite();
							snc.decode(nextData, _decoder);
Log.info("decode node for {0} depth = {1} refs = {2}", Component.printURI(snc._longhash), snc._treeDepth, snc.getRefs().size());
							byte[] hash = snc.getHash();
							if (null != hash) {
								SyncTreeEntry ste = _pbsm.getHash(hash);
								if (null == ste) {
									ste = _pbsm.addHash(null, snc);
								}
								// Should we push a hash we already have? Yes! because this
								// would have (probably) come from a different comparator and
								// we don't yet know how it matches up to the comparison we are
								// doing.
								ste.setPos(0);
								push(ste, _next);
								_currentRoot = ste;
								_state = SyncCompareState.PRELOAD;
							}
						}
						if (_state == SyncCompareState.INIT)
							_state = SyncCompareState.DONE;
						continue;
					}
					// Fall through
				case PRELOAD:
					synchronized (_timerLock) {
						if (null == getHead(_next)) {
							_keepComparing = false;
							break;
						}
					}
					if (doPreload(getHead(_next))) {
						break;
					}
					_state = SyncCompareState.COMPARE;
					// Fall through
				case COMPARE:
					doComparison();
					if (_state == SyncCompareState.COMPARE) {
						synchronized (_timerLock) {
							_keepComparing = false;
							break;
						}
					}
				case DONE:
					synchronized (this) {
						if (_pendingEntries.size() > 0) {
							_state = SyncCompareState.INIT;
							break;
						}
					}
					getHash(_currentRoot.getHash(), true);
					
				default:
					synchronized (_timerLock) {
						_keepComparing = false;
					}
					break;
				}				
				synchronized (_timerLock) {
					if (!_keepComparing)
						_needToCompare = true;
				}
			} while (_keepComparing);
		} catch (Exception ex) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, ex);} 	
		  catch (Error er) {Log.logStackTrace(Log.FAC_SYNC, Level.WARNING, er);} 
	}
	
	protected class NodeFetchHandler implements CCNContentHandler {

		public Interest handleContent(ContentObject data, Interest interest) {
			ContentName name = data.name();

			int hashComponent = name.containsWhere(Sync.SYNC_NODE_FETCH_MARKER);
			if (hashComponent < 0 || name.count() < (hashComponent + 1)) {
				if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
					Log.info(Log.FAC_SYNC, "Received incorrect node content in sync: {0}", name);
				return null;
			}
			byte[] hash = name.component(hashComponent + 2);
			if (Log.isLoggable(Log.FAC_SYNC, Level.INFO))
				Log.info(Log.FAC_SYNC, "Saw data from nodefind: hash: {0}", Component.printURI(hash));
			synchronized (this) {
				if (DataUtils.compare(hash, _currentRoot.getHash()) > 0) {
					Log.info(Log.FAC_SYNC, "Did pending add in nf");
					_pendingEntries.add(data.content());
					checkNextRound();
				} else {
					SyncTreeEntry ste = null;
					ste = _pbsm.addHash(hash, null);
					ste.setRawContent(data.content());
					ste.setPending(false);
				}
				kickCompare();
			}
			return null;
		}
	}
}
