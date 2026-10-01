package io.openems.edge.hycube.ess.statemachine;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.common.statemachine.AbstractStateMachine;
import io.openems.edge.common.statemachine.StateHandler;

public class StateMachine extends AbstractStateMachine<StateMachine.State, Context> {

	public enum State implements io.openems.edge.common.statemachine.State<State>, OptionsEnum {
		UNDEFINED(-1), //
		INIT_BATTERY(3),//
		
		READ_STATUS_WORDS(4),
		
		BEFORE_SWITCH_ON(5),
		
		SWITCH_ON(6),

		INIT_WRITE_REGISTERS(8),
		
		REWRITE_REGISTERS(9),
		
		GO_RUNNING(10), //
		RUNNING(11), //
		GO_STOPPED(20), //
		STOPPED(21), //
		ERROR(30);

		private final int value;

		private State(int value) {
			this.value = value;
		}

		@Override
		public int getValue() {
			return this.value;
		}

		@Override
		public String getName() {
			return this.name();
		}

		@Override
		public OptionsEnum getUndefined() {
			return UNDEFINED;
		}

		@Override
		public State[] getStates() {
			return State.values();
		}
	}

	public StateMachine(State initialState) {
		super(initialState);
	}

	@Override
	public StateHandler<State, Context> getStateHandler(State state) {
		return switch (state) {
		case UNDEFINED -> new UndefinedHandler();
		case INIT_BATTERY -> new InitBatteryHandler();
		case READ_STATUS_WORDS -> new ReadStatusWords();
		case BEFORE_SWITCH_ON -> new InitBeforeSwitchOn();
		case SWITCH_ON -> new SwitchBatteryConnection();
		case INIT_WRITE_REGISTERS -> new InitWriteRegisters();
		case REWRITE_REGISTERS -> new ReWriteRegisters();
		case GO_RUNNING -> new GoRunningHandler();
		case RUNNING -> new RunningHandler();
		case GO_STOPPED -> new GoStoppedHandler();
		case STOPPED -> new StoppedHandler();
		case ERROR -> new ErrorHandler();
		};
	}
}
