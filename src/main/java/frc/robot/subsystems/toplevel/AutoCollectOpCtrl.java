package frc.robot.subsystems.toplevel;

import java.util.Optional;

import org.xero1425.base.misc.XeroElapsedTimer;
import org.xero1425.base.misc.XeroTimer;
import org.xero1425.base.subsystems.swerve.common.SwerveDriveToPoseAction;
import org.xero1425.misc.BadParameterTypeException;
import org.xero1425.misc.MessageLogger;
import org.xero1425.misc.MessageType;
import org.xero1425.misc.MissingParameterException;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import frc.robot.subsystems.arm.ArmStaggeredGotoAction;
import frc.robot.subsystems.gpm.GPMCollectAction;

public class AutoCollectOpCtrl extends OperationCtrl {

    public final static boolean AddLiftStep = false ;

    private enum State {
        Idle,
        LookingForTag,
        WaitForVision,
        DrivingToLocation,
        SettlingDelay,
        DriveForward,
        DriveBack,
    }

    private double april_tag_action_threshold_ ;
    private State state_ ;

    private GPMCollectAction collect_action_ ;
    private SwerveDriveToPoseAction drive_to_action_ ;

    private ArmStaggeredGotoAction stow_arm_ ;

    private XeroTimer drive_forward_timer_ ;
    private XeroTimer drive_back_timer_ ;
    private XeroTimer wait_for_vision_timer_ ;
    private XeroTimer settling_timer_ ;
    private XeroTimer drive_forward_after_sensor_timer_ ;

    private XeroElapsedTimer overall_timer_ ;   // Measure time since auto takes over

    private boolean done_driving_forward_ ;

    private Pose2d target_pose_ ;
    
    public AutoCollectOpCtrl(Swimmy2023RobotSubsystem sub, RobotOperation oper) throws Exception {
        super(sub, oper) ;

        april_tag_action_threshold_ = sub.getSettingsValue("april-tag-collect-action-threshold").getDouble() ;
        state_ = State.Idle;

        collect_action_ = new GPMCollectAction(sub.getGPM(), oper.getGamePiece(), oper.getGround());

        stow_arm_ = new ArmStaggeredGotoAction(sub.getGPM().getArm(), "collect:retract-shelf", false);

        drive_forward_timer_ = new XeroTimer(sub.getRobot(), "collect-forward-timer", 0.9);
        drive_back_timer_ = new XeroTimer(sub.getRobot(), "collect-back-timer", 0.3);
        wait_for_vision_timer_ = new XeroTimer(sub.getRobot(), "wait-for-vision-timer", 0.2);
        settling_timer_ = new XeroTimer(sub.getRobot(), "settling", 0.2) ;
        drive_forward_after_sensor_timer_ = new XeroTimer(sub.getRobot(), "drive-forward-after-sensor-timer", 0.2);
        overall_timer_ = new XeroElapsedTimer(sub.getRobot()) ;
    }

    @Override
    public void start() throws BadParameterTypeException, MissingParameterException {
        super.start() ;
        
        state_ = State.Idle ;
    }

    @Override
    public void run() throws BadParameterTypeException, MissingParameterException {
        State orig = state_ ;

        switch(state_) {
            case Idle:
                stateIdle() ;
                break ;

            case LookingForTag:
                stateLookingForTag() ;
                break ;

            case WaitForVision:
                stateWaitForVision() ;
                break ;

            case DrivingToLocation:
                stateDrivingToLocation() ;
                break;

            case SettlingDelay:
                stateSettlingDelay() ;
                break;

            case DriveForward:
                stateDriveForward() ;
                break ;

            case DriveBack:
                stateDriveBack() ;
                break ;
        }

        if (state_ != orig) {
            MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
            logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
            logger.add("AutoCollectOpCtrl State Changes: " + orig.toString() + " -> " + state_.toString());
            logger.endMessage();
        }
    }

    @Override
    public void abort() throws BadParameterTypeException, MissingParameterException {
        switch(state_) {
            case Idle:
                break ;

            case LookingForTag:
                break ;

            case WaitForVision:
                break ;

            case DrivingToLocation:
                getRobotSubsystem().getOI().enableGamepad() ;
                getRobotSubsystem().getSwerve().enableVision(true);
                getRobotSubsystem().getSwerve().drive(new ChassisSpeeds());
                drive_to_action_.cancel() ;
                break ;        

            case SettlingDelay:
                getRobotSubsystem().getOI().enableGamepad() ;
                getRobotSubsystem().getSwerve().enableVision(true);
                getRobotSubsystem().getSwerve().drive(new ChassisSpeeds());
                break ;        
            
            case DriveForward:
                getRobotSubsystem().getOI().enableGamepad() ;
                getRobotSubsystem().getSwerve().enableVision(true);
                getRobotSubsystem().getSwerve().drive(new ChassisSpeeds());
                break; 

            case DriveBack:
                getRobotSubsystem().getOI().enableGamepad() ;
                getRobotSubsystem().getSwerve().enableVision(true);
                getRobotSubsystem().getSwerve().drive(new ChassisSpeeds());
                break ;
        }

        setDone();
        state_ = State.Idle;
    }

    private void stateIdle() {
        state_ = State.LookingForTag ;
    }

    private void stateLookingForTag() throws BadParameterTypeException, MissingParameterException {
        Optional<Alliance> a = DriverStation.getAlliance() ;
        if (!a.isPresent()) {
            MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
            logger.startMessage(MessageType.Error).add("AutoCollectOpCtrl:LookingForTag: DriverStation did not return an alliance").endMessage();
            logger.endMessage();
        }
        else {
            int tag = getRobotSubsystem().getFieldData().getLoadingStationTag(a.get());
            if (getRobotSubsystem().getLimeLight().distantToTag(tag) < april_tag_action_threshold_) {            
                getRobotSubsystem().getOI().disableGamepad();
                getRobotSubsystem().getOI().getGamePad().rumble(1.0, 0.5);
                getRobotSubsystem().getSwerve().drive(new ChassisSpeeds()) ;
                getRobotSubsystem().getGPM().setAction(collect_action_);
                overall_timer_.reset() ;
                wait_for_vision_timer_.start() ;
                state_ = State.WaitForVision ;
            }
        }
    }

    private void stateWaitForVision() throws BadParameterTypeException, MissingParameterException {
        if (wait_for_vision_timer_.isExpired()) {
            Optional<Alliance> a = DriverStation.getAlliance() ;
            if (!a.isPresent()) {
                MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
                logger.startMessage(MessageType.Error).add("AutoCollectOpCtrl:WaitForVision: DriverStation did not return an alliance").endMessage();
                logger.endMessage();
            }
            else {
                target_pose_ = getRobotSubsystem().getFieldData().getLoadingStationPose(a.get(), getOper().getSlot());
                getRobotSubsystem().getSwerve().enableVision(false);
                drive_to_action_ = new SwerveDriveToPoseAction(getRobotSubsystem().getSwerve(), target_pose_, 4.0, 2.5);
                getRobotSubsystem().getSwerve().setAction(drive_to_action_);
                state_ = State.DrivingToLocation ;
            }
        }
        else {
            MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger();
            logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
            logger.add("Waiting On Vision") ;
            logger.add("vision", getRobotSubsystem().getLimeLight().getBlueBotPose().toPose2d());
            logger.add("db", getRobotSubsystem().getSwerve().getPose());
            logger.endMessage();            
        }
    }

    private void stateDrivingToLocation() {
        if (drive_to_action_.isDone()) {
            getRobotSubsystem().getSwerve().drive(new ChassisSpeeds()) ;
            settling_timer_.start() ;
            state_ = State.SettlingDelay ;

            MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
            logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
            logger.add("starting settling timer") ;
            logger.add("duration", settling_timer_.getDuration());
            logger.endMessage();
        }
    }

    // Allow settling time after driving to location + ensure arm deployed before driving forward
    private void stateSettlingDelay() {
        MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
        logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
        logger.add("settling_timer_status") ;
        logger.add(" ").add(settling_timer_.toString());
        logger.endMessage();

        if (settling_timer_.isExpired() && collect_action_.doneRaisingArm()) {
            ChassisSpeeds speed = new ChassisSpeeds(1.0, 0.0, 0.0) ;
            getRobotSubsystem().getSwerve().drive(speed) ;
            drive_forward_timer_.start() ;
            state_ = State.DriveForward ;
            done_driving_forward_ = false ;
        }
        else {
            logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
            logger.add("in settling delay") ;
            logger.add("settling_timer", settling_timer_.isExpired());
            logger.add("collect_action", collect_action_.doneRaisingArm());
            logger.endMessage();
        }
    }

    private void stateDriveForward() {
        if (drive_forward_timer_.isExpired()) {
            getRobotSubsystem().getSwerve().drive(new ChassisSpeeds()) ;
            done_driving_forward_ = true ;
        }

        if (getRobotSubsystem().getGPM().getGrabber().getSensor() && 
            !drive_forward_after_sensor_timer_.isRunning() && 
            !done_driving_forward_) 
        {
            drive_forward_after_sensor_timer_.start();
        }

        if (drive_forward_after_sensor_timer_.isRunning() && drive_forward_after_sensor_timer_.isExpired()) {
            getRobotSubsystem().getSwerve().drive(new ChassisSpeeds()) ;
            done_driving_forward_ = true ;
        }

        if (collect_action_.isDone()) {
            ChassisSpeeds speed = new ChassisSpeeds(-3.0, 0.0, 0.0) ;
            getRobotSubsystem().getSwerve().drive(speed) ;
            drive_back_timer_.start() ;
            state_ = State.DriveBack ;
        }
    }

    private void stateDriveBack() {
        if (drive_back_timer_.isExpired()) {
            getRobotSubsystem().getSwerve().enableVision(true);
            getRobotSubsystem().getOI().enableGamepad();
            getRobotSubsystem().getOI().getGamePad().rumble(1.0, 0.5);
            getRobotSubsystem().getSwerve().drive(new ChassisSpeeds()) ;
            getRobotSubsystem().getGPM().getArm().setAction(stow_arm_);

            MessageLogger logger = getRobotSubsystem().getRobot().getMessageLogger() ;
            logger.startMessage(MessageType.Debug, getRobotSubsystem().getLoggerID());
            logger.add("AutoCollectOpCtrl duration: " + overall_timer_.elapsed());
            logger.endMessage();

            state_ = State.Idle ;
            setDone() ;
        }
    }
}
